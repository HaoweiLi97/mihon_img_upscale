package eu.kanade.tachiyomi.ui.reader

object MihonShaders {

    // Anime4K (Simplified High-Pass Sharpener) in AGSL
    val ANIME4K_AGSL = """
        uniform shader content;
        
        half4 main(float2 fragCoord) {
            half4 central = content.eval(fragCoord);
            
            // Sample neighbors for edge detection
            half4 left  = content.eval(fragCoord + float2(-1.0, 0.0));
            half4 right = content.eval(fragCoord + float2(1.0, 0.0));
            half4 up    = content.eval(fragCoord + float2(0.0, -1.0));
            half4 down  = content.eval(fragCoord + float2(0.0, 1.0));
            
            half4 edges = abs(left - central) + abs(right - central) + abs(up - central) + abs(down - central);
            float edgeStrength = (edges.r + edges.g + edges.b) / 3.0;
            
            // Apply sharpening mask
            half4 sharpened = central + (central - (left + right + up + down) * 0.25) * 0.8;
            
            return mix(central, sharpened, clamp(edgeStrength * 2.0, 0.0, 1.0));
        }
    """.trimIndent()

    // Waifu2x (Simplified Denoise) in AGSL
    val WAIFU2X_AGSL = """
        uniform shader content;
        
        half4 main(float2 fragCoord) {
            half4 central = content.eval(fragCoord);
            
            // 3x3 Average Denoise
            half4 sum = half4(0.0);
            for (float i = -1.0; i <= 1.0; i++) {
                for (float j = -1.0; j <= 1.0; j++) {
                    sum += content.eval(fragCoord + float2(i, j));
                }
            }
            half4 denoised = sum / 9.0;
            
            // Preservation of high-contrast edges
            float diff = distance(central.rgb, denoised.rgb);
            return mix(denoised, central, clamp(diff * 5.0, 0.0, 1.0));
        }
    """.trimIndent()
}
