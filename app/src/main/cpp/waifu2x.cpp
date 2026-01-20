#include "waifu2x.h"
#include "shaders.h"
#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cstring>
#include <sys/system_properties.h>
#include <thread>
#include <vector>

#define TAG "Waifu2xNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

Waifu2x::Waifu2x(int gpuid, bool _tta_mode, int num_threads) {
  vkdev = gpuid == -1 ? 0 : ncnn::get_gpu_device(gpuid);
  net.opt.num_threads = num_threads;
  waifu2x_preproc = 0;
  waifu2x_postproc = 0;
  waifu2x_preproc_tta = 0;
  waifu2x_postproc_tta = 0;
  bicubic_2x = 0;
  tta_mode = _tta_mode;
  noise = 0;
  scale = 2;
  tilesize = 128;  // Balanced speed and memory
  prepadding = 18; // Slightly reduced padding for speed, safe for 256 tile size
  progress_ptr = nullptr;
}

Waifu2x::~Waifu2x() {
  delete waifu2x_preproc;
  delete waifu2x_postproc;
  delete waifu2x_preproc_tta;
  delete waifu2x_postproc_tta;
  if (bicubic_2x) {
    bicubic_2x->destroy_pipeline(net.opt);
    delete bicubic_2x;
  }
}

int Waifu2x::load(const std::string &parampath, const std::string &modelpath) {
  net.opt.use_vulkan_compute = vkdev ? true : false;
  net.opt.use_fp16_packed = true;  // Disable FP16 packed
  net.opt.use_fp16_storage = true; // Disable FP16 storage

  net.opt.use_fp16_arithmetic =
      false; // Use FP32 arithmetic (Vulkan lacks BF16 math)
  net.opt.use_packing_layout = true; // Enable packing for better performance

  // Additional optimizations (safe for all devices)
  net.opt.use_sgemm_convolution = true;    // Use SGEMM for convolution
  net.opt.use_winograd_convolution = true; // Winograd convolution
  net.opt.use_local_pool_allocator = true; // Better memory allocation
  net.opt.use_shader_local_memory = true;  // Use shader local memory

  net.opt.num_threads = 3; // Optimized for Snapdragon multi-core architecture

  // Hardware-specific optimizations are already set in constructor
  // (use_subgroup_ops, use_cooperative_matrix, num_threads)
  // No need to override them here

  net.set_vulkan_device(vkdev);

  if (net.load_param(parampath.c_str()) != 0) {
    LOGE("Failed to load param: %s", parampath.c_str());
    return -1;
  }
  if (net.load_model(modelpath.c_str()) != 0) {
    LOGE("Failed to load model: %s", modelpath.c_str());
    return -1;
  }

  // No custom shaders for now - just use the model directly
  // The preproc/postproc will be handled in CPU

  // Create interp layer for bicubic alpha scaling
  bicubic_2x = ncnn::create_layer("Interp");
  if (!bicubic_2x) {
    LOGE("Failed to create Interp layer!");
    return -1;
  }

  bicubic_2x->vkdev = vkdev;
  ncnn::ParamDict pd;
  pd.set(0, 3); // bicubic
  pd.set(1, 2.f);
  pd.set(2, 2.f);
  bicubic_2x->load_param(pd);
  if (bicubic_2x->create_pipeline(net.opt) != 0) {
    LOGE("Failed to create Interp pipeline");
    return -1;
  }

  return 0;
}

int Waifu2x::process(const ncnn::Mat &inimage, ncnn::Mat &outimage) const {
  // Input: planar RGBA float Mat with values 0-255 from from_pixels
  // inimage has dims=3, w=width, h=height, c=4 (RGBA)

  int orig_w = inimage.w;
  int orig_h = inimage.h;

  // Use original resolution for full quality per user request
  const ncnn::Mat &work_img = inimage;

  int w = work_img.w;
  int h = work_img.h;

  LOGD("Processing image %dx%d (orig %dx%d)", w, h, orig_w, orig_h);

  // Normalization using work_img
  ncnn::Mat rgb_normalized(w, h, 3);
  bool is_grayscale = true;

  // Channel mapping from work_img
  const float *in_r = work_img.channel(0);
  const float *in_g = work_img.channel(1);
  const float *in_b = work_img.channel(2);

  float *out_b = rgb_normalized.channel(0); // B goes to channel 0
  float *out_g = rgb_normalized.channel(1); // G stays at channel 1
  float *out_r = rgb_normalized.channel(2); // R goes to channel 2

  const float norm = 1.0f / 255.0f;

  // Robust grayscale detection: allow up to 0.5% of pixels to be "colorful"
  // (noise tolerance)
  int color_pixel_count = 0;
  int color_threshold_count = w * h / 200; // 0.5%

  for (int i = 0; i < w * h; i++) {
    // Detect if pixel has significant color
    if (std::abs(in_r[i] - in_g[i]) > 5.0f ||
        std::abs(in_r[i] - in_b[i]) > 5.0f) {
      color_pixel_count++;
    }

    out_b[i] = in_b[i] * norm;
    out_g[i] = in_g[i] * norm;
    out_r[i] = in_r[i] * norm;
  }

  if (color_pixel_count > color_threshold_count) {
    is_grayscale = false;
  }
  if (is_grayscale)
    LOGD("Grayscale image detected, forcing pure grayscale output.");

  // Tiling parameters
  // Tiling parameters
  const int TILE_SIZE_X = tilesize;
  const int TILE_SIZE_Y = tilesize;

  // Create padded input to handle borders easily
  ncnn::Mat padded_input;
  ncnn::copy_make_border(rgb_normalized, padded_input, prepadding, prepadding,
                         prepadding, prepadding, ncnn::BORDER_REPLICATE, 0.f,
                         net.opt);

  // Output holder (BGR planar, normalized)
  ncnn::Mat model_out(w * scale, h * scale, 3);
  model_out.fill(
      0.f); // Initialize to black to prevent artifacts if tiling leaves gaps

  const int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
  const int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;

  for (int yi = 0; yi < ytiles; yi++) {
    for (int xi = 0; xi < xtiles; xi++) {
      // Determine input tile location in the padded image
      // Original tile starts at (xi*TILE_SIZE_X, yi*TILE_SIZE_Y)
      // Because we added 'prepadding' border, the corresponding data in
      // padded_input starts at same coords! (0,0) orig matches (prepadding,
      // prepadding) in padded. So (x,y) orig matches (x,y) offsets relative to
      // (prepadding, prepadding)? No. Padded(0,0) is top-left of border.
      // Padded(prepadding, prepadding) is Orig(0,0).
      // So Orig(x,y) is at Padded(x + prepadding, y + prepadding).
      // We want input tile covering Orig(x,y) with context radius 'prepadding'.
      // So we want Padded region starting at (x + prepadding - prepadding, ...)
      // -> (x, y). Correct! Simple.

      int x = xi * TILE_SIZE_X;
      int y = yi * TILE_SIZE_Y;

      int w_tile = std::min(TILE_SIZE_X, w - x);
      int h_tile = std::min(TILE_SIZE_Y, h - y);

      int in_tile_w = w_tile + 2 * prepadding;
      int in_tile_h = h_tile + 2 * prepadding;

      // Extract tile from padded_input
      // Since memory is planar but strided, we need to copy row by row
      ncnn::Mat in_tile(in_tile_w, in_tile_h, 3);
      for (int c = 0; c < 3; c++) {
        const float *ptr = padded_input.channel(c).row(y) + x;
        float *outptr = in_tile.channel(c);

        for (int i = 0; i < in_tile_h; i++) {
          memcpy(outptr, ptr, in_tile_w * sizeof(float));
          ptr += padded_input.w;
          outptr += in_tile.w;
        }
      }

      // Run inference on tile
      ncnn::Mat out_tile;
      {
        ncnn::Extractor ex = net.create_extractor();
        ex.set_light_mode(true);
        // Use generic indices to support all models (CUNet, UpConv7, etc)
        // input_indexes()[0] is usually the input blob
        // output_indexes()[last] is usually the output blob
        if (net.input_indexes().empty() || net.output_indexes().empty()) {
          LOGE("Model has no inputs or outputs!");
          return -1;
        }
        ex.input(net.input_indexes()[0], in_tile);
        ex.extract(net.output_indexes()[net.output_indexes().size() - 1],
                   out_tile);
      }

      if (out_tile.empty() || out_tile.c < 3) {
        LOGE("Inference tile failed or invalid channels (c=%d) at %d,%d",
             out_tile.c, xi, yi);
        // Fill with black or copy input?
        // Copying input (resized) is hard here. Just skip (leaves
        // black/garbage)
        continue;
      }

      // Copy valid center to model_out
      int out_x = x * scale;
      int out_y = y * scale;
      int out_w_tile = w_tile * scale;
      int out_h_tile = h_tile * scale;
      int out_pad = prepadding * scale;

      // Heuristic: If output is smaller than expected (considering padding),
      // assume it's cropped (valid only) Expected with padding: out_w_tile +
      // 2*out_pad
      int src_offset_x = out_pad;
      int src_offset_y = out_pad;

      if (out_tile.w < out_w_tile + 2 * out_pad ||
          out_tile.h < out_h_tile + 2 * out_pad) {
        // Assume no padding or partial padding. trying 0 offset.
        src_offset_x = 0;
        src_offset_y = 0;
        // If it's STILL too small, we will clamp in the loop.
      }

      for (int c = 0; c < 3; c++) {
        for (int i = 0; i < out_h_tile; i++) {
          int dst_row_idx = out_y + i;
          int src_row_idx = src_offset_y + i;

          if (dst_row_idx >= model_out.h)
            break;
          if (src_row_idx >= out_tile.h)
            break;

          float *dst = model_out.channel(c).row(dst_row_idx) + out_x;
          const float *src =
              out_tile.channel(c).row(src_row_idx) + src_offset_x;

          int copy_w = out_w_tile;
          // Clamp width
          if (out_x + copy_w > model_out.w)
            copy_w = model_out.w - out_x;
          if (src_offset_x + copy_w > out_tile.w)
            copy_w = out_tile.w - src_offset_x;

          if (copy_w > 0) {
            memcpy(dst, src, copy_w * sizeof(float));
          }
        }
      }

      // Update progress
      if (progress_ptr) {
        progress_ptr->store((xi + yi * xtiles + 1) * 100 / (xtiles * ytiles));
      }

      // Check for abort signal
      if (should_abort_ptr && should_abort_ptr->load()) {
        LOGD("Waifu2x process aborted by signal");
        return -1;
      }

      // Configurable sleep between tiles for cooling and UI responsiveness
      // This runs regardless of ui_busy state to ensure consistent thermal
      // behavior
      if (tile_sleep_ms > 0) {
        if (xi == 0 && yi == 0) {
          LOGD("THROTTLE: sleep %dms per tile. Tilesize=%d. Total tiles: %d x "
               "%d = %d",
               tile_sleep_ms, tilesize, xtiles, ytiles, xtiles * ytiles);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(tile_sleep_ms));
      } else {
        if (xi == 0 && yi == 0) {
          LOGD("THROTTLE: inactive (0ms). Tilesize=%d. Total tiles: %d x %d = "
               "%d",
               tilesize, xtiles, ytiles, xtiles * ytiles);
        }
      }
    }
  }
  if (progress_ptr) {
    progress_ptr->store(100);
  }

  LOGD("Model output: %dx%d with %d channels", model_out.w, model_out.h,
       model_out.c);

  // model_out is BGR planar normalized (0-1), already at target size (2x, 3x or
  // 4x)
  int target_w = orig_w * scale;
  int target_h = orig_h * scale;

  // Process alpha channel from ORIGINAL input (no resolution loss)
  ncnn::Mat alpha_out;
  bool has_alpha = (inimage.c >= 4);

  if (has_alpha) {
    ncnn::Mat alpha_in = inimage.channel_range(3, 1);
    if (scale == 2) {
      bicubic_2x->forward(alpha_in, alpha_out, net.opt);
    } else {
      // For 3x/4x, use ncnn resize as fallback for alpha
      ncnn::resize_bilinear(alpha_in, alpha_out, target_w, target_h, net.opt);
    }
  }

  // Convert model output (BGR) back to RGBA
  outimage.create(target_w, target_h, 4);

  const float *final_b = model_out.channel(0);
  const float *final_g = model_out.channel(1);
  const float *final_r = model_out.channel(2);
  const float *alpha_data = has_alpha ? (float *)alpha_out.data : nullptr;

  float *out_rgba_r = outimage.channel(0);
  float *out_rgba_g = outimage.channel(1);
  float *out_rgba_b = outimage.channel(2);
  float *out_rgba_a = outimage.channel(3);

  for (int i = 0; i < target_w * target_h; i++) {
    float r = final_r[i] * 255.0f;
    float g = final_g[i] * 255.0f;
    float b = final_b[i] * 255.0f;

    if (is_grayscale) {
      // For grayscale input, ensure pure grayscale output by averaging channels
      // This prevents the AI model from introducing subtle color tints or
      // chroma noise
      float gray = (r + g + b) / 3.0f;
      r = g = b = gray;
    }

    // Clamp to valid range
    out_rgba_r[i] = std::max(0.0f, std::min(255.0f, r));
    out_rgba_g[i] = std::max(0.0f, std::min(255.0f, g));
    out_rgba_b[i] = std::max(0.0f, std::min(255.0f, b));
    out_rgba_a[i] =
        alpha_data ? alpha_data[i] : 255.0f; // Default to opaque if no alpha
  }

  LOGD("Output image: %dx%d", outimage.w, outimage.h);

  return 0;
}

int Waifu2x::process_cpu(const ncnn::Mat &inimage, ncnn::Mat &outimage) const {
  // Same as process for now since we're not using custom shaders
  return process(inimage, outimage);
}
