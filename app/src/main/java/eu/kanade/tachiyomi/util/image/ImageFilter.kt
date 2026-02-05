package eu.kanade.tachiyomi.util.image

import android.graphics.Bitmap
import android.util.Log
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import tachiyomi.core.common.preference.Preference

object ImageFilter {

    private const val TAG = "ImageFilter"

    /**
     * Apply ink filter if enabled in preferences.
     */
    fun applyInkFilterIfEnabled(bitmap: Bitmap, preferences: ReaderPreferences): Bitmap {
        val isEnabled = preferences.inkFilter().get()
        if (!isEnabled) return bitmap
        
        val bleeding = preferences.inkBleedingIntensity().get()
        val bump = preferences.inkBumpIntensity().get()
        val original = preferences.inkOriginalIntensity().get()
        
        return applyInkFilter(
            bitmap,
            bleeding,
            bump,
            original
        )
    }

    /**
     * Apply ink filter effects to manga image
     * Simulates physical ink behavior on paper
     */
    fun applyInkFilter(
        bitmap: Bitmap,
        inkBleedingIntensity: Int,
        inkBumpIntensity: Int,
        inkOriginalIntensity: Int
    ): Bitmap {
        // CRITICAL: Ensure we have a mutable SOFTWARE bitmap.
        // HARDWARE bitmaps crash on getPixels() and RenderScript.
        var result = if (bitmap.config == Bitmap.Config.HARDWARE || !bitmap.isMutable) {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            bitmap
        }
        
        // Capture safe software original for blending
        val safeOrigin = result
        
        Log.d(TAG, "applyInkFilter called. Bleed: $inkBleedingIntensity, Bump: $inkBumpIntensity, Bitmap: ${bitmap.width}x${bitmap.height}, Config: ${bitmap.config}")
        
        // Apply bleeding effect
        if (inkBleedingIntensity > 0) {
            Log.d(TAG, "Applying Ink Bleeding...")
            val start = System.currentTimeMillis()
            result = applyInkBleeding(result, inkBleedingIntensity / 100f)
            Log.d(TAG, "Ink Bleeding finished in ${System.currentTimeMillis() - start}ms")
        } else {
             Log.d(TAG, "Skipping Ink Bleeding (intensity=0)")
        }
        
        // Apply bump mapping effect
        if (inkBumpIntensity > 0) {
            Log.d(TAG, "Applying Ink Bump Mapping...")
            val start = System.currentTimeMillis()
            result = applyInkBumpMapping(result, inkBumpIntensity / 100f)
            Log.d(TAG, "Ink Bump Mapping finished in ${System.currentTimeMillis() - start}ms")
        } else {
             Log.d(TAG, "Skipping Bump Mapping (intensity=0)")
        }
        
        // Blend with original image based on inkOriginalIntensity
        if (inkOriginalIntensity > 0 && result != safeOrigin) {
            val blendFactor = inkOriginalIntensity / 100f
            Log.d(TAG, "Blending with original image at ${inkOriginalIntensity}%")
            
            val width = result.width
            val height = result.height
            val blended = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            val originalPixels = IntArray(width * height)
            val processedPixels = IntArray(width * height)
            val blendedPixels = IntArray(width * height)
            
            safeOrigin.getPixels(originalPixels, 0, width, 0, 0, width, height)
            result.getPixels(processedPixels, 0, width, 0, 0, width, height)
            
            // Parallel blending for performance
            val chunks = Runtime.getRuntime().availableProcessors()
            val chunkSize = (height + chunks - 1) / chunks
            
            runBlocking {
                val jobs = (0 until chunks).map { i ->
                    launch(Dispatchers.Default) {
                        val startY = i * chunkSize
                        val endY = min(startY + chunkSize, height)
                        if (startY >= endY) return@launch
                        
                        for (y in startY until endY) {
                            val rowStart = y * width
                            for (x in 0 until width) {
                                val idx = rowStart + x
                                val orig = originalPixels[idx]
                                val proc = processedPixels[idx]
                                
                                val aOrig = (orig shr 24) and 0xFF
                                val rOrig = (orig shr 16) and 0xFF
                                val gOrig = (orig shr 8) and 0xFF
                                val bOrig = orig and 0xFF
                                
                                val aProc = (proc shr 24) and 0xFF
                                val rProc = (proc shr 16) and 0xFF
                                val gProc = (proc shr 8) and 0xFF
                                val bProc = proc and 0xFF
                                
                                // Linear interpolation
                                val a = (aProc * (1 - blendFactor) + aOrig * blendFactor).toInt()
                                val r = (rProc * (1 - blendFactor) + rOrig * blendFactor).toInt()
                                val g = (gProc * (1 - blendFactor) + gOrig * blendFactor).toInt()
                                val b = (bProc * (1 - blendFactor) + bOrig * blendFactor).toInt()
                                
                                blendedPixels[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                }
                jobs.joinAll()
            }
            
            blended.setPixels(blendedPixels, 0, width, 0, 0, width, height)
            
            // Recycle the processed bitmap if it's not the original
            if (result != bitmap && result != safeOrigin) {
                result.recycle()
            }
            result = blended
        }
        
        return result
    }

    /**
     * Apply ink bleeding and absorption effect
     * Simulates ink spreading along paper fibers
     */
    private fun applyInkBleeding(bitmap: Bitmap, intensity: Float): Bitmap {
        try {
            val width = bitmap.width
            val height = bitmap.height
            
            // Software Blur: Downscale -> Upscale
            // This is fast and simulates diffusion well
            val scale = 0.25f // 25% size (Less blurry = tighter spread)
            val smallW = (width * scale).toInt().coerceAtLeast(1)
            val smallH = (height * scale).toInt().coerceAtLeast(1)
            
            val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)
            val blurred = Bitmap.createScaledBitmap(small, width, height, true)
            
            if (small != bitmap && small != blurred) {
                small.recycle()
            }
            
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            val blurredPixels = IntArray(width * height)
            val originalPixels = IntArray(width * height)
            val finalPixels = IntArray(width * height)
            
            bitmap.getPixels(originalPixels, 0, width, 0, 0, width, height)
            blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)
            blurred.recycle()
            
            val random = java.util.Random()
            // Reduced parameters significantly
            val noiseBase = (intensity * 0.05f).coerceIn(0f, 0.1f)
            // Linear scaling from 0. At intensity 1 (0.01), bleed is 0.015 (very subtle).
            // At intensity 100 (1.0), bleed is 1.5 (max clamp in logic).
            val bleedStrength = intensity * 1.5f
            
            // Parallel Processing using Coroutines to speed up pixel iteration
            val chunks = Runtime.getRuntime().availableProcessors()
            val chunkSize = (height + chunks - 1) / chunks
            
            runBlocking {
                val jobs = (0 until chunks).map { i ->
                    launch(Dispatchers.Default) {
                        val startY = i * chunkSize
                        val endY = min(startY + chunkSize, height)
                        if (startY >= endY) return@launch
                        
                        // Thread-local Random to avoid synchronization overhead
                        val localRandom = java.util.Random()
                        
                        for (y in startY until endY) {
                            val rowStart = y * width
                            for (x in 0 until width) {
                                val idx = rowStart + x
                                val orig = originalPixels[idx]
                                val blur = blurredPixels[idx]
                                
                                val a = (orig shr 24) and 0xFF
                                val rOrig = (orig shr 16) and 0xFF
                                val gOrig = (orig shr 8) and 0xFF
                                val bOrig = orig and 0xFF
                                
                                // Compare brightness
                                val lumaOrig = (rOrig + gOrig + bOrig) / 3
                                val rBlur = (blur shr 16) and 0xFF
                                val gBlur = (blur shr 8) and 0xFF
                                val bBlur = blur and 0xFF
                                val lumaBlur = (rBlur + gBlur + bBlur) / 3
                                
                                var nr = rOrig
                                var ng = gOrig
                                var nb = bOrig
                                
                                // Bleed logic: mixing
                                if (lumaBlur < lumaOrig) {
                                   val factor = bleedStrength.coerceAtMost(1.0f)
                                   nr = (rOrig * (1 - factor) + rBlur * factor).toInt()
                                   ng = (gOrig * (1 - factor) + gBlur * factor).toInt()
                                   nb = (bOrig * (1 - factor) + bBlur * factor).toInt()
                                }
                                
                                // Add paper noise to ink areas
                                if (lumaOrig < 100) {
                                     if (localRandom.nextFloat() < noiseBase) {
                                         val noise = localRandom.nextInt(40) + 20
                                         nr = (nr + noise).coerceAtMost(255)
                                         ng = (ng + noise).coerceAtMost(255)
                                         nb = (nb + noise).coerceAtMost(255)
                                     }
                                }
                                
                                finalPixels[idx] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
                            }
                        }
                    }
                }
                jobs.joinAll()
            }
            
            output.setPixels(finalPixels, 0, width, 0, 0, width, height)
            return output
        } catch (e: Exception) {
            Log.e(TAG, "Error applying ink bleeding", e)
            return bitmap
        }
    }

    /**
     * Apply ink bump mapping effect
     * Simulates 3D relief of dried ink on paper
     */
    private fun applyInkBumpMapping(bitmap: Bitmap, intensity: Float): Bitmap {
        try {
            val width = bitmap.width
            val height = bitmap.height
            
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val outputPixels = IntArray(width * height)
            
            // Ultra 3D relief logic
            val depth = 5f + intensity * 40f
            val lightAngle = 135.0 // Top-left light
            val rad = Math.toRadians(lightAngle)
            val lx = Math.cos(rad).toFloat()
            val ly = Math.sin(rad).toFloat()
            val nz = 128f / depth
            
            val shadowStr = 0.6f * intensity
            val highlightStr = 0.3f * intensity

            // 1. Pre-calculate Lighting Lookup Table (LUT)
            val lut = FloatArray(512 * 512)
            
            runBlocking {
                val jobs = (0 until 8).map { i -> 
                   launch(Dispatchers.Default) {
                       val startDy = -255 + i * (512/8) // approx
                       val endDy = -255 + (i+1) * (512/8)
                       for (dy in startDy until endDy) {
                           if (dy > 255) continue
                           for (dx in -255..255) {
                               val nx = -dx.toFloat()
                               val ny = -dy.toFloat()
                               val mag = sqrt(nx * nx + ny * ny + nz * nz)
                               val dot = (nx * lx + ny * ly + nz) / mag
                               
                               val factor = if (dot < 0) {
                                   1.0f + dot * shadowStr
                               } else {
                                   1.0f + dot * highlightStr
                               }
                               val idx = (dy + 255) * 512 + (dx + 255)
                               if (idx in lut.indices) lut[idx] = factor
                           }
                       }
                   }
                }
                jobs.joinAll()
            }

            // 2. Parallel Pixel Processing
            val chunks = Runtime.getRuntime().availableProcessors()
            val chunkSize = (height + chunks - 1) / chunks

            runBlocking {
                val jobs = (0 until chunks).map { i ->
                    launch(Dispatchers.Default) {
                        val startY = max(1, i * chunkSize)
                        val endY = min(startY + chunkSize, height - 1) // -1 margin
                        if (startY >= endY) return@launch
                        
                        for (y in startY until endY) {
                            val rowOffset = y * width
                            for (x in 1 until width - 1) {
                                val idx = rowOffset + x
                                
                                val pLeft = pixels[idx - 1]
                                val grayLeft = ((pLeft shr 16 and 0xFF) + (pLeft shr 8 and 0xFF) + (pLeft and 0xFF)) / 3
                                
                                val pRight = pixels[idx + 1]
                                val grayRight = ((pRight shr 16 and 0xFF) + (pRight shr 8 and 0xFF) + (pRight and 0xFF)) / 3
                                
                                val dx = grayRight - grayLeft
                                
                                val pUp = pixels[idx - width]
                                val grayUp = ((pUp shr 16 and 0xFF) + (pUp shr 8 and 0xFF) + (pUp and 0xFF)) / 3
                                
                                val pDown = pixels[idx + width]
                                val grayDown = ((pDown shr 16 and 0xFF) + (pDown shr 8 and 0xFF) + (pDown and 0xFF)) / 3
                                
                                val dy = grayDown - grayUp
                                
                                val lutIdx = (dy + 255) * 512 + (dx + 255)
                                val factor = lut[lutIdx]
                                
                                val current = pixels[idx]
                                val a = (current shr 24 and 0xFF)
                                val r = (current shr 16 and 0xFF)
                                val g = (current shr 8 and 0xFF)
                                val b = (current and 0xFF)
                                
                                val nr = (r * factor).toInt().coerceIn(0, 255)
                                val ng = (g * factor).toInt().coerceIn(0, 255)
                                val nb = (b * factor).toInt().coerceIn(0, 255)
                                
                                outputPixels[idx] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
                            }
                        }
                    }
                }
                jobs.joinAll()
            }
            
            for (i in 0 until width) {
                outputPixels[i] = pixels[i]
                outputPixels[(height - 1) * width + i] = pixels[(height - 1) * width + i]
            }
            for (i in 0 until height) {
                outputPixels[i * width] = pixels[i * width]
                outputPixels[i * width + width - 1] = pixels[i * width + width - 1]
            }
            
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            output.setPixels(outputPixels, 0, width, 0, 0, width, height)
            return output
        } catch (e: Exception) {
            Log.e(TAG, "Error applying bump mapping", e)
            return bitmap
        }
    }
}
