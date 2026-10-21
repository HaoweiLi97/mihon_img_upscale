package eu.kanade.tachiyomi.util.qnn

import android.content.Context
import android.os.Build
import android.util.Log
import eu.kanade.tachiyomi.util.npu.NpuModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Compiles bundled FP16 DLCs once per model/runtime/device, outside the image cache. */
object QnnModelContexts {
    const val CACHE_VERSION = "20"
    private const val RUNTIME_VERSION = "2.49.0"
    private const val TAG = "QnnModelContexts"

    private val mutableStates = MutableStateFlow<Map<String, NpuModelStatus>>(emptyMap())
    val states = mutableStates.asStateFlow()

    init {
        runCatching { System.loadLibrary("waifu2x-jni") }
    }

    internal fun contextFile(context: Context, modelName: String): File {
        val manifest =
            JSONObject(context.assets.open("qnn-models/manifest.json").bufferedReader().use { it.readText() })
        require(manifest.has(modelName) && modelName.matches(Regex("[a-z0-9-]+"))) { "Unknown QNN model: $modelName" }
        val modelHash = manifest.getString(modelName)
        val architecture = QualcommHtp.architecture(context) ?: error("No supported FP16 HTP runtime")
        // A driver/OS update can invalidate compiled binaries even on the same HTP architecture.
        val identity = listOf(
            CACHE_VERSION,
            RUNTIME_VERSION,
            modelHash,
            architecture.toString(),
            Build.FINGERPRINT,
            Build.SUPPORTED_ABIS.first(),
        ).joinToString("|")
        val key = sha256(identity.toByteArray()).take(32)
        return File(context.noBackupFilesDir, "qnn-contexts/$modelName/$key/context.bin")
    }

    /** Blocking: call on an IO worker. Serialization also prevents duplicate preparations from the UI and decoder. */
    @Synchronized
    fun prepare(context: Context, modelName: String, retry: Boolean = false): File? {
        val previous = mutableStates.value[modelName]
        if (previous is NpuModelStatus.Failed && !retry) return null
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
                it.isDirectory && it != directory
            }?.forEach { it.deleteRecursively() }
            binary
        }.onFailure { error ->
            Log.w(TAG, "Unable to prepare $modelName; enhancement skipped", error)
            setState(modelName, NpuModelStatus.Failed(error.message.orEmpty()))
        }.getOrNull()
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
}
