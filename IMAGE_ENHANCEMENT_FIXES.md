# Waifu2x Engine Optimization and Configuration

## Optimized Configuration Summary

We have fine-tuned the Waifu2x (Real-CUGAN) engine to balance performance, image quality, and UI responsiveness.

### 1. Precision & Stability (BF16 / FP32)
*   **Storage**: **BF16** (`use_bf16_storage = true`)
    *   Reduces memory bandwidth usage by 50% compared to FP32, same as FP16.
    *   Offers better dynamic range and stability than FP16, fixing potential overflow issues.
*   **Arithmetic**: **FP32** (`use_fp16_arithmetic = false`)
    *   We disabled FP16 arithmetic to completely resolve "flower screen" (flickering/artifacts) issues caused by driver incompatibilities or precision loss on some devices.
    *   While slower than FP16 math, stability is prioritized.

### 2. Tiling & Padding
*   **Tilesize**: **128**
    *   Kept small to ensure each GPU task is short, preventing UI lockup (jank) during sliding/scrolling.
*   **Padding**: **18**
    *   Restored to the official recommended value for Real-CUGAN 2x.
    *   This eliminates the black grid lines and edge blurring issues observed when padding was too small (e.g., 6 or 10).

### 3. Alpha Channel & Sharpness
*   **Scaling Algorithm**: **Nearest Neighbor**
    *   Switched from Bicubic to Nearest Neighbor for scaling the Alpha channel.
    *   This prevents the "fuzzy edge" look on text and sharp lines, keeping fonts crisp.

### 4. UI Responsiveness (Throttling)
*   **Conditional Sleep**:
    *   Added logic to force a **40ms sleep** after each tile processing **ONLY** when the UI is detected as busy (e.g., user is touching the screen).
    *   This guarantees that the UI thread gets enough GPU time to render smooth animations even when heavy processing is happening in the background.

## Key Files Modified
*   `app/src/main/cpp/waifu2x.cpp`

## Code Snippet (Current Configuration)
```cpp
  net.opt.use_fp16_packed = false;     // Disable FP16 packed
  net.opt.use_fp16_storage = false;   // Disable FP16 storage
  net.opt.use_bf16_storage = true;    // Use BF16 storage (stable & bandwidth efficient)
  net.opt.use_fp16_arithmetic = false; // Use FP32 arithmetic (Vulkan lacks BF16 math)

  // ...

  tilesize = 128;  // Balanced speed and memory
  prepadding = 18; // Standard padding to prevent artifacts

  // ...

  // Alpha Scaling
  pd.set(0, 1); // nearest neighbor for sharpest edges/fonts
```
