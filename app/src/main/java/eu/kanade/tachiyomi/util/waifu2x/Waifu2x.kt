package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Waifu2x image upscaler using ncnn.
 * Provides 2x upscaling with denoising for manga images.
 */
object Waifu2x {

    @Volatile private var isInitialized = false
    @Volatile private var isRealCuganInitialized = false
    @Volatile private var isRealEsrganInitialized = false
    @Volatile private var isNoseInitialized = false
    @Volatile private var isWaifu2xInitialized = false
    @Volatile private var isAnime4kInitialized = false

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
    
            isInitialized = nativeInit(modelDir, noiseLevel, scale)
            if (isInitialized) {
                // Invalidate all other models
                isRealCuganInitialized = false
                isRealEsrganInitialized = false
                isNoseInitialized = false
                isWaifu2xInitialized = false // Wait, I am Waifu2x (generic)
                isAnime4kInitialized = false
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
    private data class RealCuganConfig(val noise: Int, val scale: Int, val isPro: Boolean)
    @Volatile private var lastRealCuganConfig: RealCuganConfig? = null

    fun initRealCugan(context: Context, noiseLevel: Int, scale: Int, isPro: Boolean = false, tileSleepMs: Int = 0, tileSize: Int = 128): Boolean {
        val newConfig = RealCuganConfig(noiseLevel, scale, isPro)

        // Fast path: if already initialized with same config, just update performance params and return
        if (isRealCuganInitialized && lastRealCuganConfig == newConfig) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        return synchronized(this) {
            val currentConfig = RealCuganConfig(noiseLevel, scale, isPro)
            
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
    
            isRealCuganInitialized = nativeInitRealCugan(modelDir, noiseLevel, scale, tileSleepMs)
            if (isRealCuganInitialized) {
                lastRealCuganConfig = currentConfig
                nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
                
                // Invalidate all other models
                isInitialized = false
                isRealEsrganInitialized = false
                isNoseInitialized = false
                isWaifu2xInitialized = false
                isAnime4kInitialized = false
                
                android.util.Log.d("Waifu2x", "Initialized Real-CUGAN: isPro=$isPro, noise=$noiseLevel, scale=$scale, tileSleepMs=$tileSleepMs, tileSize=$tileSize")
            }
            isRealCuganInitialized
        }
    }

    // Track Real-ESRGAN config
    private var lastRealEsrganScale: Int? = null

    fun initRealESRGAN(context: Context, scale: Int, tileSleepMs: Int = 0, tileSize: Int = 128): Boolean = synchronized(this) {
        // Force reinit if config changed
        if (lastRealEsrganScale != scale) {
            android.util.Log.d("Waifu2x", "Real-ESRGAN scale changed from $lastRealEsrganScale to $scale, reinitializing...")
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

        isRealEsrganInitialized = nativeInitRealESRGAN(modelDir, scale)
        if (isRealEsrganInitialized) {
            lastRealEsrganScale = scale
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            
            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isNoseInitialized = false
            isWaifu2xInitialized = false
            isAnime4kInitialized = false
            
            android.util.Log.d("Waifu2x", "Initialized Real-ESRGAN: scale=$scale, tileSleepMs=$tileSleepMs, tileSize=$tileSize")
        }
        isRealEsrganInitialized
    }

    fun initNose(context: Context, tileSleepMs: Int = 0, tileSize: Int = 128): Boolean = synchronized(this) {
        if (isNoseInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        val modelDir = extractModelsToCache(context, "waifu2x-models-nose")
        if (modelDir == null) {
            return false
        }

        isNoseInitialized = nativeInitNose(modelDir)
        if (isNoseInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            
            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isWaifu2xInitialized = false
            isAnime4kInitialized = false
            
            android.util.Log.d("Waifu2x", "Initialized Nose model, tileSleepMs=$tileSleepMs, tileSize=$tileSize")
        }
        isNoseInitialized
    }

    // Track Waifu2x config
    private data class Waifu2xConfig(val noise: Int, val scale: Int)
    private var lastWaifu2xConfig: Waifu2xConfig? = null

    fun initWaifu2x(context: Context, noise: Int, scale: Int, tileSleepMs: Int = 0, tileSize: Int = 128): Boolean = synchronized(this) {
        val newConfig = Waifu2xConfig(noise, scale)
        
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
        
        isWaifu2xInitialized = nativeInit(modelDir, noise, scale)
        if (isWaifu2xInitialized) {
            lastWaifu2xConfig = newConfig
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            
            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isNoseInitialized = false
            isAnime4kInitialized = false
            
            android.util.Log.d("Waifu2x", "Initialized Waifu2x: noise=$noise, scale=$scale, tileSleepMs=$tileSleepMs, tileSize=$tileSize")
        }
        isWaifu2xInitialized
    }

    fun initWaifu2xUpconv7(context: Context, noise: Int, scale: Int, tileSleepMs: Int = 0, tileSize: Int = 128): Boolean = synchronized(this) {
        val newConfig = Waifu2xConfig(noise, scale)

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

        isWaifu2xInitialized = nativeInitWaifu2xUpconv7(modelDir, noise, scale)
        if (isWaifu2xInitialized) {
            lastWaifu2xConfig = newConfig
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            
            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isNoseInitialized = false
            isAnime4kInitialized = false
            
            android.util.Log.d("Waifu2x", "Initialized Waifu2x UpConv7: noise=$noise, scale=$scale, tileSleepMs=$tileSleepMs, tileSize=$tileSize")
        }
        isWaifu2xInitialized
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
            return nativeProcessRealCugan(argbBitmap, id)
        } finally {
            processingId = -1
            if (argbBitmap !== input) {
                argbBitmap.recycle()
            }
        }
    }

    fun getProgress(): Long = nativeGetProgress()

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
        nativeDestroy()
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
        if (isInitialized || isRealCuganInitialized || isRealEsrganInitialized || isNoseInitialized || isWaifu2xInitialized || isAnime4kInitialized) {
            nativeDestroy()
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isNoseInitialized = false
            isWaifu2xInitialized = false
            isAnime4kInitialized = false
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
            val modelFiles = assetManager.list(assetPath) ?: return null

            for (filename in modelFiles) {
                val outFile = File(cacheDir, filename)
                if (!outFile.exists()) {
                    assetManager.open("$assetPath/$filename").use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            cacheDir.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun setUiBusy(busy: Boolean) {
        nativeSetUiBusy(busy)
    }

    // Native methods
    private external fun nativeInit(modelDir: String, noiseLevel: Int, scale: Int): Boolean
    private external fun nativeInitWaifu2xUpconv7(modelDir: String, noiseLevel: Int, scale: Int): Boolean
    private external fun nativeProcess(input: Bitmap, id: Int): Bitmap?
    private external fun nativeDestroy()
    private external fun nativeSetUiBusy(busy: Boolean)
    
    // ... (Anime4K signatures unchanged)

    private external fun nativeInitAnime4K(shaders: Array<String>, names: Array<String>): Boolean
    private external fun nativeProcessAnime4K(input: Bitmap): Bitmap?

    private external fun nativeInitRealCugan(modelDir: String, noiseLevel: Int, scale: Int, tileSleepMs: Int): Boolean
    private external fun nativeUpdatePerformanceConfig(tileSleepMs: Int, tileSize: Int)
    
    fun updatePerformance(tileSleepMs: Int, tileSize: Int) {
        if (isRealCuganInitialized || isRealEsrganInitialized || isNoseInitialized || isWaifu2xInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
        }
    }
    
    private external fun nativeInitRealESRGAN(modelDir: String, scale: Int): Boolean
    private external fun nativeInitNose(modelDir: String): Boolean
    private external fun nativeProcessRealCugan(input: Bitmap, id: Int): Bitmap?
    private external fun nativeGetProgress(): Long
}
