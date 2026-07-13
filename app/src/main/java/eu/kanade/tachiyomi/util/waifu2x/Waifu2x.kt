package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Waifu2x image upscaler using ncnn.
 * Provides 2x upscaling with denoising for manga images.
 */
object Waifu2x {

    // Bump when bundled model assets change so existing installations refresh their cache.
    private const val BUNDLED_MODEL_CACHE_VERSION = "2"

    @Volatile private var isInitialized = false
    @Volatile private var isRealCuganInitialized = false
    @Volatile private var isRealEsrganInitialized = false
    @Volatile private var isNoseInitialized = false
    @Volatile private var isWaifu2xInitialized = false
    @Volatile private var isAnime4kInitialized = false
    @Volatile private var isW2xExInitialized = false

    init {
        try {
            System.loadLibrary("waifu2x-jni")
        } catch (e: UnsatisfiedLinkError) {
            // Native library not available
        }
    }

    fun init(context: Context, noiseLevel: Int = 2, scale: Int = 2): Boolean {
        if (isInitialized) return true
        
        return synchronized(this) {
            if (isInitialized) return true
    
            val modelDir = extractModelsToCache(context, "waifu2x-models")
            if (modelDir == null) {
                return false
            }
    
            isInitialized = nativeInit(modelDir, noiseLevel, scale, 3, 0)
            if (isInitialized) {
                // Invalidate all other models
                isRealCuganInitialized = false
                isRealEsrganInitialized = false
                isNoseInitialized = false
                isWaifu2xInitialized = false // Wait, I am Waifu2x (generic)
                isAnime4kInitialized = false
                isW2xExInitialized = false
            }
            isInitialized
        }
    }

    /**
     * Process a bitmap image with Waifu2x upscaling.
     * 
     * @param input Input bitmap (will not be modified)
     * @return Upscaled bitmap, or null if processing failed
     */
    fun process(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isInitialized) return null

        // Ensure input is in ARGB_8888 format
        val argbBitmap = if (input.config != Bitmap.Config.ARGB_8888) {
            input.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            input
        }

        return nativeProcess(argbBitmap, id)
    }

    // Track current config to detect changes (excludes tileSleepMs since that doesn't require model reload)
    private data class RealCuganConfig(val noise: Int, val scale: Int, val isPro: Boolean, val jobs: Int, val precision: Int)
    @Volatile private var lastRealCuganConfig: RealCuganConfig? = null

    fun initRealCugan(context: Context, noiseLevel: Int, scale: Int, isPro: Boolean = false, tileSleepMs: Int = 0, tileSize: Int = 128, jobs: Int = 3, precision: Int = 0): Boolean {
        val newConfig = RealCuganConfig(noiseLevel, scale, isPro, jobs.coerceIn(1, 8), precision.coerceIn(0, 3))

        // Fast path: if already initialized with same config, just update performance params and return
        if (isRealCuganInitialized && lastRealCuganConfig == newConfig) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        return synchronized(this) {
            val currentConfig = RealCuganConfig(noiseLevel, scale, isPro, jobs.coerceIn(1, 8), precision.coerceIn(0, 3))
            
            // Force reinit only if model parameters changed (not tileSleepMs)
            if (lastRealCuganConfig != currentConfig) {
                android.util.Log.d("Waifu2x", "Config changed from $lastRealCuganConfig to $currentConfig, reinitializing...")
                isRealCuganInitialized = false
            }
            
            if (isRealCuganInitialized) {
                // Model already loaded, just update performance params
                nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
                return true
            }
    
            val assetPath = if (isPro) "realcugan-pro-models" else "realcugan-models"
            val modelDir = extractModelsToCache(context, assetPath)
            if (modelDir == null) {
                return false
            }
    
            isRealCuganInitialized = nativeInitRealCugan(modelDir, noiseLevel, scale, tileSleepMs, currentConfig.jobs, currentConfig.precision)
            if (isRealCuganInitialized) {
                lastRealCuganConfig = currentConfig
                nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
                
                // Invalidate all other models
                isInitialized = false
                isRealEsrganInitialized = false
                isNoseInitialized = false
                isWaifu2xInitialized = false
                isAnime4kInitialized = false
                isW2xExInitialized = false
                
                android.util.Log.d("Waifu2x", "Initialized Real-CUGAN: isPro=$isPro, noise=$noiseLevel, scale=$scale, tileSleepMs=$tileSleepMs, tileSize=$tileSize, jobs=${currentConfig.jobs}, precision=${currentConfig.precision}")
            }
            isRealCuganInitialized
        }
    }

    // Track Real-ESRGAN config
    private data class RealEsrganConfig(val scale: Int, val jobs: Int, val precision: Int)
    private var lastRealEsrganConfig: RealEsrganConfig? = null

    fun initRealESRGAN(context: Context, scale: Int, tileSleepMs: Int = 0, tileSize: Int = 128, jobs: Int = 3, precision: Int = 0): Boolean = synchronized(this) {
        val config = RealEsrganConfig(scale, jobs.coerceIn(1, 8), precision.coerceIn(0, 3))
        // Force reinit if config changed
        if (lastRealEsrganConfig != config) {
            android.util.Log.d("Waifu2x", "Real-ESRGAN config changed from $lastRealEsrganConfig to $config, reinitializing...")
            isRealEsrganInitialized = false
        }
        
        if (isRealEsrganInitialized) {
            // Update throttling
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        // Asset path: realesrgan-models/v3-anime
        val modelDir = extractModelsToCache(context, "realesrgan-models/v3-anime")
        if (modelDir == null) {
            return false
        }

        isRealEsrganInitialized = nativeInitRealESRGAN(modelDir, scale, config.jobs, config.precision)
        if (isRealEsrganInitialized) {
            lastRealEsrganConfig = config
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            
            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isNoseInitialized = false
            isWaifu2xInitialized = false
            isAnime4kInitialized = false
            isW2xExInitialized = false
            
            android.util.Log.d("Waifu2x", "Initialized Real-ESRGAN: scale=$scale, tileSleepMs=$tileSleepMs, tileSize=$tileSize, jobs=${config.jobs}, precision=${config.precision}")
        }
        isRealEsrganInitialized
    }

    private data class GenericModelConfig(val jobs: Int, val precision: Int)
    private var lastNoseConfig: GenericModelConfig? = null

    fun initNose(context: Context, tileSleepMs: Int = 0, tileSize: Int = 128, jobs: Int = 3, precision: Int = 0): Boolean = synchronized(this) {
        val config = GenericModelConfig(jobs.coerceIn(1, 8), precision.coerceIn(0, 3))
        if (lastNoseConfig != config) {
            isNoseInitialized = false
        }
        if (isNoseInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        val modelDir = extractModelsToCache(context, "waifu2x-models-nose")
        if (modelDir == null) {
            return false
        }

        isNoseInitialized = nativeInitNose(modelDir, config.jobs, config.precision)
        if (isNoseInitialized) {
            lastNoseConfig = config
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            
            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isWaifu2xInitialized = false
            isAnime4kInitialized = false
            isW2xExInitialized = false
            
            android.util.Log.d("Waifu2x", "Initialized Nose model, tileSleepMs=$tileSleepMs, tileSize=$tileSize, jobs=${config.jobs}, precision=${config.precision}")
        }
        isNoseInitialized
    }

    // Track Waifu2x config
    private data class Waifu2xConfig(val noise: Int, val scale: Int, val jobs: Int, val precision: Int)
    private var lastWaifu2xConfig: Waifu2xConfig? = null

    fun initWaifu2x(context: Context, noise: Int, scale: Int, tileSleepMs: Int = 0, tileSize: Int = 128, jobs: Int = 3, precision: Int = 0): Boolean = synchronized(this) {
        val newConfig = Waifu2xConfig(noise, scale, jobs.coerceIn(1, 8), precision.coerceIn(0, 3))
        
        // Force reinit if config changed
        if (lastWaifu2xConfig != newConfig) {
            android.util.Log.d("Waifu2x", "Waifu2x config changed from $lastWaifu2xConfig to $newConfig, reinitializing...")
            isWaifu2xInitialized = false
        }
        
        if (isWaifu2xInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }
        
        val modelDir = extractModelsToCache(context, "waifu2x-models")
        if (modelDir == null) return false
        
        isWaifu2xInitialized = nativeInit(modelDir, noise, scale, newConfig.jobs, newConfig.precision)
        if (isWaifu2xInitialized) {
            lastWaifu2xConfig = newConfig
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            
            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isNoseInitialized = false
            isAnime4kInitialized = false
            isW2xExInitialized = false
            
            android.util.Log.d("Waifu2x", "Initialized Waifu2x: noise=$noise, scale=$scale, tileSleepMs=$tileSleepMs, tileSize=$tileSize, jobs=${newConfig.jobs}, precision=${newConfig.precision}")
        }
        isWaifu2xInitialized
    }

    fun initWaifu2xUpconv7(context: Context, noise: Int, scale: Int, tileSleepMs: Int = 0, tileSize: Int = 128, jobs: Int = 3, precision: Int = 0): Boolean = synchronized(this) {
        val newConfig = Waifu2xConfig(noise, scale, jobs.coerceIn(1, 8), precision.coerceIn(0, 3))

        // Force reinit if config changed
        if (lastWaifu2xConfig != newConfig) {
            android.util.Log.d("Waifu2x", "Waifu2x UpConv7 config changed from $lastWaifu2xConfig to $newConfig, reinitializing...")
            isWaifu2xInitialized = false
        }

        if (isWaifu2xInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        val modelDir = extractModelsToCache(context, "waifu2x-models-upconv7")
        if (modelDir == null) return false

        isWaifu2xInitialized = nativeInitWaifu2xUpconv7(modelDir, noise, scale, newConfig.jobs, newConfig.precision)
        if (isWaifu2xInitialized) {
            lastWaifu2xConfig = newConfig
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            
            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isNoseInitialized = false
            isAnime4kInitialized = false
            isW2xExInitialized = false
            
            android.util.Log.d("Waifu2x", "Initialized Waifu2x UpConv7: noise=$noise, scale=$scale, tileSleepMs=$tileSleepMs, tileSize=$tileSize, jobs=${newConfig.jobs}, precision=${newConfig.precision}")
        }
        isWaifu2xInitialized
    }

    private data class W2xExConfig(val model: Int, val jobs: Int, val precision: Int)
    private var lastW2xExConfig: W2xExConfig? = null

    private data class W2xExModel(val stem: String, val scale: Int)

    private fun w2xExModelFor(model: Int): W2xExModel? {
        return when (model) {
            6 -> W2xExModel("Universal-Fast-W2xEX", 2)
            7 -> W2xExModel("Omni-Mini-W2xEX", 2)
            8 -> W2xExModel("Omni-MiniV2-W2xEX", 2)
            9 -> W2xExModel("Photo-Small-W2xEX", 2)
            10 -> W2xExModel("Anime-HQ-W4xEX", 4)
            11 -> W2xExModel("Photo-HQ-W4xEX", 4)
            12 -> W2xExModel("spanx2_ch48", 2)
            13 -> W2xExModel("spanx2_ch52", 2)
            14 -> W2xExModel("spanx4_ch48", 4)
            15 -> W2xExModel("spanx4_ch52", 4)
            else -> null
        }
    }

    fun w2xExScaleFor(model: Int): Int? = w2xExModelFor(model)?.scale

    fun isW2xExModel(model: Int): Boolean = w2xExModelFor(model) != null

    fun initW2xEx(context: Context, model: Int, tileSleepMs: Int = 0, tileSize: Int = 128, jobs: Int = 3, precision: Int = 0): Boolean = synchronized(this) {
        val selectedModel = w2xExModelFor(model) ?: return false
        val config = W2xExConfig(model, jobs.coerceIn(1, 8), precision.coerceIn(0, 3))
        if (lastW2xExConfig != config) {
            isW2xExInitialized = false
        }

        if (isW2xExInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        val assetPath = if (model in 12..15) {
            "span-ncnn-vulkan/${selectedModel.stem}"
        } else {
            "w2xex-esrgan/${selectedModel.stem}"
        }
        val modelDir = extractModelsToCache(context, assetPath) ?: return false

        isW2xExInitialized = nativeInitW2xEx(modelDir, selectedModel.stem, selectedModel.scale, config.jobs, config.precision)
        if (isW2xExInitialized) {
            lastW2xExConfig = config
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)

            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isNoseInitialized = false
            isWaifu2xInitialized = false
            isAnime4kInitialized = false

            android.util.Log.d("Waifu2x", "Initialized generic ncnn model: ${selectedModel.stem}, scale=${selectedModel.scale}, jobs=${config.jobs}, precision=${config.precision}")
        }
        isW2xExInitialized
    }

    // Reuse processRealCugan for all generic ncnn models
    // But check specific flags
    // Reuse processRealCugan for all generic ncnn models
    // But check specific flags
    fun processRealESRGAN(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isRealEsrganInitialized) return null
        return processBitmapHelper(input, id)
    }
    
    fun processNose(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isNoseInitialized) return null
        return processBitmapHelper(input, id)
    }

    fun processWaifu2x(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isWaifu2xInitialized) return null
        return processBitmapHelper(input, id)
    }

    fun processW2xEx(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isW2xExInitialized) return null
        return processBitmapHelper(input, id)
    }
    
    @Volatile var processingId: Int = -1

    private fun processBitmapHelper(input: Bitmap, id: Int): Bitmap? {
        if (input.isRecycled) return null
        
        val argbBitmap = if (input.config != Bitmap.Config.ARGB_8888) {
            try {
                input.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }
        } else {
            input
        } ?: return null
        
        processingId = id
        try {
            val result = nativeProcessRealCugan(argbBitmap, id)
            return if (result === argbBitmap) null else result
        } finally {
            processingId = -1
            if (argbBitmap !== input) {
                argbBitmap.recycle()
            }
        }
    }

    /**
     * Get the raw packed progress value from native code.
     * Format: [ID (upper 32 bits)] [Progress (lower 32 bits)]
     */
    fun getProgress(): Long = nativeGetProgress()
    
    /**
     * Get only the progress percentage (0-100) from the packed value.
     */
    fun getProgressPercent(): Int {
        val packed = nativeGetProgress()
        return (packed and 0xFFFFFFFF).toInt()
    }
    
    /**
     * Get only the processing ID from the packed value.
     */
    fun getProgressId(): Int {
        val packed = nativeGetProgress()
        return (packed shr 32).toInt()
    }

    /**
     * Reset Real-CUGAN to allow re-initialization with new settings.
     */
    fun resetRealCugan() {
        isInitialized = false
        isRealCuganInitialized = false
        isRealEsrganInitialized = false
        isNoseInitialized = false
        isWaifu2xInitialized = false
        isAnime4kInitialized = false
        isW2xExInitialized = false
        lastRealCuganConfig = null
        lastRealEsrganConfig = null
        lastNoseConfig = null
        lastWaifu2xConfig = null
        lastW2xExConfig = null
        nativeDestroy()
    }

    /**
     * Ask any active native upscaling operation to stop at its next cancellation check.
     */
    fun abortProcessing() {
        nativeAbortProcessing()
    }

    /**
     * Process bitmap with Real-CUGAN.
     */
    fun processRealCugan(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isRealCuganInitialized) return null
        return processBitmapHelper(input, id)
    }

    /**
     * Release native resources.
     */
    fun destroy() {
        if (isInitialized || isRealCuganInitialized || isRealEsrganInitialized || isNoseInitialized || isWaifu2xInitialized || isAnime4kInitialized || isW2xExInitialized) {
            nativeDestroy()
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isNoseInitialized = false
            isWaifu2xInitialized = false
            isAnime4kInitialized = false
            isW2xExInitialized = false
        }
    }

    /**
     * Initialize Anime4K with specific mode.
     */
    fun initAnime4K(context: Context, mode: Int): Boolean {
        if (isAnime4kInitialized) return true

        val assetManager = context.assets
        val shaders = mutableListOf<String>()
        val names = mutableListOf<String>()

        fun addShader(name: String) {
            val content = assetManager.open("anime4k/$name").bufferedReader().use { it.readText() }
            shaders.add(content)
            names.add(name)
        }

        try {
            addShader("Anime4K_Clamp_Highlights.glsl")
            when (mode) {
                0 -> addShader("Anime4K_Restore_CNN_M.glsl") // Fast
                1 -> addShader("Anime4K_Restore_CNN_VL.glsl") // High
                2 -> { // Ultra
                    addShader("Anime4K_Restore_CNN_VL.glsl")
                    addShader("Anime4K_Upscale_CNN_x2_VL.glsl")
                }
            }
        } catch (e: Exception) {
            return false
        }

        isAnime4kInitialized = nativeInitAnime4K(shaders.toTypedArray(), names.toTypedArray())
        // Invalidate all other models
        if (isAnime4kInitialized) {
             isInitialized = false
             isRealCuganInitialized = false
             isRealEsrganInitialized = false
             isNoseInitialized = false
             isWaifu2xInitialized = false
             isW2xExInitialized = false
        }
        return isAnime4kInitialized
    }

    /**
     * Process bitmap with Anime4K.
     */
    fun processAnime4K(input: Bitmap): Bitmap? {
        if (!isAnime4kInitialized || input.isRecycled) return null

        val argbBitmap = try {
            if (input.config != Bitmap.Config.ARGB_8888) {
                input.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true) // Must be mutable for in-place
            }
        } catch (e: Exception) {
            null
        } ?: return null

        try {
            return nativeProcessAnime4K(argbBitmap)
        } finally {
            // We don't recycle argbBitmap if it's the same as input, 
            // but here it's always a copy (true).
            // Actually, nativeProcessAnime4K returns the SAME bitmap (in-place)
            // so we SHOULD NOT recycle it here if it's the result.
        }
    }


    private fun extractModelsToCache(context: Context, assetPath: String): String? {
        return try {
            val cacheDir = File(context.cacheDir, assetPath)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            val assetManager = context.assets
            val modelFiles = assetManager.list(assetPath).orEmpty()
            val assetVersionFile = File(cacheDir, ".bundled-model-version")
            val refreshBundledModels = modelFiles.isNotEmpty() &&
                assetVersionFile.takeIf(File::exists)?.readText() != BUNDLED_MODEL_CACHE_VERSION

            for (filename in modelFiles) {
                val outFile = File(cacheDir, filename)
                if (refreshBundledModels || !outFile.exists()) {
                    assetManager.open("$assetPath/$filename").use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            if (refreshBundledModels) {
                assetVersionFile.writeText(BUNDLED_MODEL_CACHE_VERSION)
            }

            if (!cacheDir.hasNcnnModels()) {
                downloadReleaseModels(assetPath, cacheDir)
            }

            if (!cacheDir.hasNcnnModels()) {
                downloadDirectModels(assetPath, cacheDir)
            }

            if (!cacheDir.hasNcnnModels()) {
                return null
            }

            cacheDir.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private data class ModelReleaseSource(
        val url: String,
        val entryPrefix: String,
        val stripPrefix: String = "",
    )

    private data class DirectModelSource(
        val baseUrl: String,
        val files: List<String>,
    )

    private fun releaseSourceFor(assetPath: String): ModelReleaseSource? {
        return when (assetPath) {
            "realcugan-models" -> ModelReleaseSource(
                url = "https://github.com/nihui/realcugan-ncnn-vulkan/releases/download/20220728/realcugan-ncnn-vulkan-20220728-ubuntu.zip",
                entryPrefix = "realcugan-ncnn-vulkan-20220728-ubuntu/models-se/",
            )
            "realcugan-pro-models" -> ModelReleaseSource(
                url = "https://github.com/nihui/realcugan-ncnn-vulkan/releases/download/20220728/realcugan-ncnn-vulkan-20220728-ubuntu.zip",
                entryPrefix = "realcugan-ncnn-vulkan-20220728-ubuntu/models-pro/",
            )
            "waifu2x-models-nose" -> ModelReleaseSource(
                url = "https://github.com/nihui/realcugan-ncnn-vulkan/releases/download/20220728/realcugan-ncnn-vulkan-20220728-ubuntu.zip",
                entryPrefix = "realcugan-ncnn-vulkan-20220728-ubuntu/models-nose/",
            )
            "waifu2x-models" -> ModelReleaseSource(
                url = "https://github.com/nihui/waifu2x-ncnn-vulkan/releases/download/20250915/waifu2x-ncnn-vulkan-20250915-linux.zip",
                entryPrefix = "waifu2x-ncnn-vulkan-20250915-linux/models-cunet/",
            )
            "waifu2x-models-upconv7" -> ModelReleaseSource(
                url = "https://github.com/nihui/waifu2x-ncnn-vulkan/releases/download/20250915/waifu2x-ncnn-vulkan-20250915-linux.zip",
                entryPrefix = "waifu2x-ncnn-vulkan-20250915-linux/models-upconv_7_anime_style_art_rgb/",
            )
            "realesrgan-models/v3-anime" -> ModelReleaseSource(
                url = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-ubuntu.zip",
                entryPrefix = "models/realesr-animevideov3-",
                stripPrefix = "realesr-animevideov3-",
            )
            else -> null
        }
    }

    private fun directSourceFor(assetPath: String): DirectModelSource? {
        val w2xExStem = assetPath.removePrefix("w2xex-esrgan/").takeIf { it != assetPath }
        if (w2xExStem != null) {
            val supported = setOf(
                "Universal-Fast-W2xEX",
                "Omni-Mini-W2xEX",
                "Omni-MiniV2-W2xEX",
                "Photo-Small-W2xEX",
                "Anime-HQ-W4xEX",
                "Photo-HQ-W4xEX",
            )
            if (w2xExStem !in supported) return null

            return DirectModelSource(
                baseUrl = "https://huggingface.co/randomblock1/W2xEX-ESRGAN/resolve/main",
                files = listOf("$w2xExStem.param", "$w2xExStem.bin"),
            )
        }

        val spanStem = assetPath.removePrefix("span-ncnn-vulkan/").takeIf { it != assetPath }
        if (spanStem != null) {
            val supported = setOf(
                "spanx2_ch48",
                "spanx2_ch52",
                "spanx4_ch48",
                "spanx4_ch52",
            )
            if (spanStem !in supported) return null

            return DirectModelSource(
                baseUrl = "https://raw.githubusercontent.com/TNTwise/SPAN-ncnn-vulkan/master/models",
                files = listOf("$spanStem.param", "$spanStem.bin"),
            )
        }

        return null
    }

    private fun downloadReleaseModels(assetPath: String, cacheDir: File) {
        val source = releaseSourceFor(assetPath) ?: return
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            ZipInputStream(BufferedInputStream(connection.inputStream)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.startsWith(source.entryPrefix)) {
                        zip.closeEntry()
                        continue
                    }
                    val rawName = entry.name.substringAfterLast('/')
                    if (!rawName.endsWith(".param") && !rawName.endsWith(".bin")) {
                        zip.closeEntry()
                        continue
                    }
                    val filename = rawName.removePrefix(source.stripPrefix)
                    val outFile = File(cacheDir, filename)
                    outFile.outputStream().use { output ->
                        zip.copyTo(output)
                    }
                    zip.closeEntry()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadDirectModels(assetPath: String, cacheDir: File) {
        val source = directSourceFor(assetPath) ?: return
        source.files.forEach { filename ->
            val outFile = File(cacheDir, filename)
            if (outFile.exists() && outFile.length() > 0L) return@forEach

            val tempFile = File(cacheDir, "$filename.tmp")
            val url = "${source.baseUrl}/$filename"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 120_000
                instanceFollowRedirects = true
            }
            try {
                BufferedInputStream(connection.inputStream).use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (!tempFile.renameTo(outFile)) {
                    tempFile.delete()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun File.hasNcnnModels(): Boolean {
        val files = listFiles().orEmpty()
        return files.any { it.extension == "param" } && files.any { it.extension == "bin" }
    }

    fun setUiBusy(busy: Boolean) {
        nativeSetUiBusy(busy)
    }

    fun scaleBitmapNative(input: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (input.isRecycled) return null
        if (input.width == targetWidth && input.height == targetHeight) return input

        val argbBitmap = if (input.config != Bitmap.Config.ARGB_8888) {
            try {
                input.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }
        } else {
            input
        } ?: return null

        return try {
            nativeScaleBitmap(argbBitmap, targetWidth, targetHeight)
        } finally {
            if (argbBitmap !== input) {
                argbBitmap.recycle()
            }
        }
    }

    // Native methods
    private external fun nativeInit(modelDir: String, noiseLevel: Int, scale: Int, jobs: Int, precision: Int): Boolean
    private external fun nativeInitWaifu2xUpconv7(modelDir: String, noiseLevel: Int, scale: Int, jobs: Int, precision: Int): Boolean
    private external fun nativeInitW2xEx(modelDir: String, modelStem: String, scale: Int, jobs: Int, precision: Int): Boolean
    private external fun nativeProcess(input: Bitmap, id: Int): Bitmap?
    private external fun nativeDestroy()
    private external fun nativeAbortProcessing()
    private external fun nativeSetUiBusy(busy: Boolean)
    
    // ... (Anime4K signatures unchanged)

    private external fun nativeInitAnime4K(shaders: Array<String>, names: Array<String>): Boolean
    private external fun nativeProcessAnime4K(input: Bitmap): Bitmap?

    private external fun nativeInitRealCugan(modelDir: String, noiseLevel: Int, scale: Int, tileSleepMs: Int, jobs: Int, precision: Int): Boolean
    private external fun nativeUpdatePerformanceConfig(tileSleepMs: Int, tileSize: Int)
    
    fun updatePerformance(tileSleepMs: Int, tileSize: Int) {
        if (isRealCuganInitialized || isRealEsrganInitialized || isNoseInitialized || isWaifu2xInitialized || isW2xExInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
        }
    }
    
    private external fun nativeInitRealESRGAN(modelDir: String, scale: Int, jobs: Int, precision: Int): Boolean
    private external fun nativeInitNose(modelDir: String, jobs: Int, precision: Int): Boolean
    private external fun nativeProcessRealCugan(input: Bitmap, id: Int): Bitmap?
    private external fun nativeScaleBitmap(input: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap?
    private external fun nativeGetProgress(): Long
}
