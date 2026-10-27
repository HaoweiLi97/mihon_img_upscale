package eu.kanade.tachiyomi.util.qnn

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import eu.kanade.tachiyomi.util.npu.NpuModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Compiles bundled FP16 DLCs once per model/runtime/device, outside the image cache. */
object QnnModelContexts {
    const val CACHE_VERSION = "21"
    const val PRECISION_W8A16 = 4
    private const val RUNTIME_VERSION = "2.49.0"
    private const val TAG = "QnnModelContexts"

    private val mutableStates = MutableStateFlow<Map<String, NpuModelStatus>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private val mutableFp16Supported = MutableStateFlow<Boolean?>(null)
    val fp16Supported = mutableFp16Supported.asStateFlow()

    init {
        runCatching { System.loadLibrary("waifu2x-jni") }
    }

    /** Call on an IO worker. Unknown runtime errors are not cached as unsupported. */
    @Synchronized
    fun detectFp16Support(context: Context): Boolean? {
        mutableFp16Supported.value?.let { return it }
        val result = runCatching { nativeFp16Support(context.applicationInfo.nativeLibraryDir) }.getOrDefault(-1)
        val supported = when (result) {
            1 -> true
            0 -> false
            else -> null
        }
        if (supported != null) mutableFp16Supported.value = supported
        return supported
    }

    fun stateKey(modelName: String, precision: Int): String =
        if (precision == PRECISION_W8A16) "$modelName:w8a16" else modelName

    internal fun contextFile(context: Context, modelName: String, precision: Int = 0): File {
        val manifest =
            JSONObject(context.assets.open("qnn-models/manifest.json").bufferedReader().use { it.readText() })
        require(manifest.has(modelName) && modelName.matches(Regex("[a-z0-9-]+"))) { "Unknown QNN model: $modelName" }
        val modelHash = manifest.getString(modelName)
        val architecture = QualcommHtp.architecture(context) ?: error("No supported HTP runtime")
        // A driver/OS update can invalidate compiled binaries even on the same HTP architecture.
        val identity = listOf(
            CACHE_VERSION,
            RUNTIME_VERSION,
            modelHash,
            architecture.toString(),
            Build.FINGERPRINT,
            Build.SUPPORTED_ABIS.first(),
            if (precision ==
                PRECISION_W8A16
            ) {
                "local-w8a16-1|${QnnCalibrationPacks.identity(context, modelName)}"
            } else {
                "fp16"
            },
        ).joinToString("|")
        val key = sha256(identity.toByteArray()).take(32)
        val variant = if (precision == PRECISION_W8A16) "w8a16/" else ""
        return File(context.noBackupFilesDir, "qnn-contexts/$modelName/$variant$key/context.bin")
    }

    /** Blocking: call on an IO worker. Serialization also prevents duplicate preparations from the UI and decoder. */
    @Synchronized
    fun prepare(context: Context, modelName: String, retry: Boolean = false, precision: Int = 0): File? {
        val stateKey = stateKey(modelName, precision)
        val previous = mutableStates.value[stateKey]
        if (previous is NpuModelStatus.Failed && !retry) return null
        if (precision != PRECISION_W8A16 && detectFp16Support(context) == false) {
            setState(
                stateKey,
                NpuModelStatus.Failed("FP16 is unavailable on this device; select local W8A16 calibration"),
            )
            return null
        }
        if (precision == PRECISION_W8A16) {
            return runCatching {
                val binary = contextFile(context, modelName, precision)
                if (isCommitted(binary)) {
                    setState(stateKey, NpuModelStatus.Ready(binary))
                    binary
                } else {
                    setState(stateKey, NpuModelStatus.CalibrationRequired)
                    null
                }
            }.onFailure { setState(stateKey, NpuModelStatus.Failed(it.message.orEmpty())) }.getOrNull()
        }
        return runCatching {
            val binary = contextFile(context, modelName)
            val directory = requireNotNull(binary.parentFile)
            check(directory.isDirectory || directory.mkdirs()) { "Unable to create NPU model directory" }
            val checksum = File(directory, "context.sha256")
            if (binary.length() > 0 && checksum.isFile && checksum.readText() == sha256(binary)) {
                setState(modelName, NpuModelStatus.Ready(binary))
                Log.d(TAG, "Reusing persisted FP16 context: $modelName")
                return@runCatching binary
            }
            setState(modelName, NpuModelStatus.Preparing)
            // An uncommitted/modified binary must never be mistaken for a prepared context.
            check(!binary.exists() || binary.delete()) { "Unable to replace invalid NPU context" }
            checksum.delete()
            val dlc = File(directory, "model.dlc")
            val temporary = File(directory, "model.dlc.partial")
            try {
                context.assets.open("qnn-models/$modelName.dlc").use { input ->
                    temporary.outputStream().use(input::copyTo)
                }
                check(temporary.renameTo(dlc)) { "Unable to extract NPU model" }
                val started = android.os.SystemClock.elapsedRealtime()
                val error =
                    nativeCompile(dlc.absolutePath, binary.absolutePath, context.applicationInfo.nativeLibraryDir)
                check(error.isEmpty()) { error }
                check(binary.length() > 0) { "NPU compilation produced an empty context" }
                val pendingChecksum = File(directory, "context.sha256.partial")
                pendingChecksum.writeText(sha256(binary))
                check(pendingChecksum.renameTo(checksum)) { "Unable to commit NPU context checksum" }
                Log.i(
                    TAG,
                    "Compiled FP16 $modelName on device in ${android.os.SystemClock.elapsedRealtime() - started} ms",
                )
            } finally {
                temporary.delete()
                dlc.delete()
            }
            setState(modelName, NpuModelStatus.Ready(binary))
            // Reclaim obsolete versions only after a replacement has been successfully prepared.
            directory.parentFile?.listFiles()?.filter {
                it.isDirectory && it != directory && it.name.matches(Regex("[a-f0-9]{32}"))
            }?.forEach { it.deleteRecursively() }
            binary
        }.onFailure { error ->
            Log.w(TAG, "Unable to prepare $modelName; enhancement skipped", error)
            setState(modelName, NpuModelStatus.Failed(error.message.orEmpty()))
        }.getOrNull()
    }

    /** Explicit user action: downloads encrypted crops and calibrates/quantizes on this device. */
    suspend fun prepareCalibrated(context: Context, modelName: String): File? = withContext(Dispatchers.IO) {
        val stateKey = stateKey(modelName, PRECISION_W8A16)
        val job = coroutineContext[Job]
        try {
            val binary = contextFile(context, modelName, PRECISION_W8A16)
            if (isCommitted(binary)) {
                setState(stateKey, NpuModelStatus.Ready(binary))
                return@withContext binary
            }
            QnnCalibrationPacks.withSamples(
                context,
                modelName,
                onProgress = { downloaded, total -> setState(stateKey, NpuModelStatus.Downloading(downloaded, total)) },
            ) { samples ->
                synchronized(this@QnnModelContexts) {
                    job?.ensureActive()
                    val directory = requireNotNull(binary.parentFile)
                    check(directory.isDirectory || directory.mkdirs())
                    // Another preparation may have finished while this one downloaded.
                    if (isCommitted(binary)) {
                        setState(stateKey, NpuModelStatus.Ready(binary))
                        return@synchronized binary
                    }
                    val dlc = File(directory, "calibration-model.dlc")
                    val partial = File(directory, "context.bin.partial")
                    val checksum = File(directory, "context.sha256")
                    try {
                        binary.delete()
                        partial.delete()
                        checksum.delete()
                        context.assets.open("qnn-models/$modelName.dlc").use { input ->
                            dlc.outputStream().use(input::copyTo)
                        }
                        val error = nativeCalibrate(
                            dlc.absolutePath,
                            samples.absolutePath,
                            binary.absolutePath,
                            context.applicationInfo.nativeLibraryDir,
                            CalibrationProgress { stage, current, total ->
                                if (job?.isActive == false) {
                                    false
                                } else {
                                    setState(stateKey, NpuModelStatus.Calibrating(stage, current, total))
                                    true
                                }
                            },
                        )
                        job?.ensureActive()
                        check(error.isEmpty()) { error }
                        check(binary.length() > 0) { "Local calibration produced an empty context" }
                        val pending = File(directory, "context.sha256.partial")
                        pending.writeText(sha256(binary))
                        check(pending.renameTo(checksum)) { "Unable to commit locally calibrated context" }
                        setState(stateKey, NpuModelStatus.Ready(binary))
                        binary
                    } finally {
                        dlc.delete()
                        partial.delete()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            setState(stateKey, NpuModelStatus.CalibrationRequired)
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "On-device W8A16 calibration failed for $modelName", error)
            setState(stateKey, NpuModelStatus.Failed(error.message.orEmpty()))
            null
        }
    }

    private fun isCommitted(binary: File): Boolean {
        val checksum = File(binary.parentFile, "context.sha256")
        return binary.length() > 0 && checksum.isFile && checksum.readText() == sha256(binary)
    }

    private fun setState(modelName: String, status: NpuModelStatus) {
        mutableStates.update { it + (modelName to status) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private external fun nativeCompile(dlcPath: String, contextPath: String, nativeLibraryDir: String): String
    private external fun nativeFp16Support(nativeLibraryDir: String): Int

    @Keep
    fun interface CalibrationProgress {
        fun update(stage: Int, completed: Int, total: Int): Boolean
    }

    private external fun nativeCalibrate(
        dlcPath: String,
        sampleDirectory: String,
        contextPath: String,
        nativeLibraryDir: String,
        progress: CalibrationProgress,
    ): String
}
