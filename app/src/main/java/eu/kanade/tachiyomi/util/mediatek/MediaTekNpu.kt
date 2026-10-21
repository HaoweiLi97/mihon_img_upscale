package eu.kanade.tachiyomi.util.mediatek

import android.content.Context
import android.os.Build
import android.util.Log
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.npu.NpuModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** LiteRT compiles FP16 models on the user's MediaTek device and owns the compiler cache. */
object MediaTekNpu {
    const val CACHE_VERSION = "1"
    private const val RUNTIME_VERSION = "2.3.0"
    private val mutableStates = MutableStateFlow<Map<String, NpuModelStatus>>(emptyMap())
    val states = mutableStates.asStateFlow()

    init {
        runCatching { System.loadLibrary("waifu2x-jni") }
    }

    fun isDeviceSupported(): Boolean = Build.VERSION.SDK_INT >= 31 &&
        Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
        (
            Build.SOC_MANUFACTURER.contains("mediatek", ignoreCase = true) ||
                Build.HARDWARE.startsWith("mt", ignoreCase = true)
            )

    fun isActive(): Boolean = runCatching { nativeIsActive() }.getOrDefault(false)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                hash.update(buffer, 0, size)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    /** Blocks during compilation. Failed models are retried only by an explicit UI request. */
    @Synchronized
    fun prepare(context: Context, modelName: String, retry: Boolean = false): File? =
        prepareInternal(context, modelName, retry, cpuValidationOnly = false)

    /** Exercises the same C ABI and tile implementation on test devices without an MTK NPU. */
    @Synchronized
    internal fun prepareForCpuValidation(context: Context, modelName: String): File? {
        check(BuildConfig.DEBUG)
        return prepareInternal(context, modelName, retry = true, cpuValidationOnly = true)
    }

    @Synchronized
    fun initialize(context: Context, modelName: String): Boolean {
        val model = prepare(context, modelName) ?: return false
        val error = runCatching {
            configure(context, modelName, model, cpuTest = false, prepareOnly = false)
        }.getOrElse { it.message ?: "Unable to initialize MediaTek NPU" }
        if (error.isNotEmpty()) {
            mutableStates.update { it + (modelName to NpuModelStatus.Failed(error)) }
            return false
        }
        return isActive()
    }

    private fun configure(context: Context, name: String, file: File, cpuTest: Boolean, prepareOnly: Boolean): String {
        val scale = if ("-x3-" in name) 3 else 2
        val padding = when {
            name.startsWith("realcugan-") -> if (scale == 3) 14 else 18
            name.startsWith("realesrgan-general-") -> 32
            name.startsWith("realesrgan-") -> 16
            name.startsWith("span-") -> 24
            else -> 10
        }
        val cache = File(file.parentFile, "compiler-cache")
        check(cache.isDirectory || cache.mkdirs()) { "Unable to create compiler cache" }
        return nativeInitialize(
            file.absolutePath,
            cache.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            scale,
            padding,
            cpuTest,
            prepareOnly,
        )
    }

    private fun prepareInternal(
        context: Context,
        modelName: String,
        retry: Boolean,
        cpuValidationOnly: Boolean,
    ): File? {
        if (!cpuValidationOnly && mutableStates.value[modelName] is NpuModelStatus.Failed && !retry) return null
        return runCatching {
            check(cpuValidationOnly || isDeviceSupported()) { "This device has no supported MediaTek NPU" }
            val manifest = JSONObject(
                context.assets.open("litert-models/manifest.json").bufferedReader().use { it.readText() },
            )
            require(modelName.matches(Regex("[a-z0-9-]+")) && manifest.has(modelName)) { "Unknown MediaTek model" }
            val modelHash = manifest.getString(modelName)
            val identity = listOf(
                CACHE_VERSION,
                RUNTIME_VERSION,
                modelHash,
                Build.FINGERPRINT,
                Build.SUPPORTED_ABIS.first(),
            ).joinToString("|")
            val key = digest(identity.toByteArray()).take(32)
            val root = if (cpuValidationOnly) "litert-cpu-validation" else "mediatek-npu"
            val directory = File(context.noBackupFilesDir, "$root/$modelName/$key")
            check(directory.isDirectory || directory.mkdirs()) { "Unable to create model directory" }
            val model = File(directory, "model.tflite")
            if (!model.isFile || digest(model) != modelHash) {
                val pending = File(directory, "model.tflite.partial")
                try {
                    context.assets.open("litert-models/$modelName.tflite").use { input ->
                        pending.outputStream().use(input::copyTo)
                    }
                    check(digest(pending) == modelHash) { "MediaTek model checksum mismatch" }
                    check(!model.exists() || model.delete()) { "Unable to replace model" }
                    check(pending.renameTo(model)) { "Unable to commit model" }
                } finally {
                    pending.delete()
                }
            }
            if (!cpuValidationOnly && mutableStates.value[modelName] is NpuModelStatus.Ready) return@runCatching model
            if (!cpuValidationOnly) mutableStates.update { it + (modelName to NpuModelStatus.Preparing) }
            val error = configure(context, modelName, model, cpuValidationOnly, prepareOnly = !cpuValidationOnly)
            check(error.isEmpty()) { error }
            if (!cpuValidationOnly) mutableStates.update { it + (modelName to NpuModelStatus.Ready(model)) }
            directory.parentFile?.listFiles()?.filter {
                it.isDirectory && it != directory
            }?.forEach { it.deleteRecursively() }
            model
        }.onFailure { error ->
            Log.w("MediaTekNpu", "Unable to prepare $modelName; enhancement skipped", error)
            if (!cpuValidationOnly) {
                mutableStates.update { it + (modelName to NpuModelStatus.Failed(error.message.orEmpty())) }
            }
        }.getOrNull()
    }

    private external fun nativeInitialize(
        model: String,
        cache: String,
        libraries: String,
        scale: Int,
        padding: Int,
        cpuTest: Boolean,
        prepareOnly: Boolean,
    ): String
    private external fun nativeIsActive(): Boolean
}
