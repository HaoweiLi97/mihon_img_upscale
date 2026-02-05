#include "waifu2x.h"
#include "shaders.h"
#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cstring>
#include <deque>
#include <future>
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

int Waifu2x::process(const ncnn::Mat &inimage, void *out_pixels, int out_stride,
                     std::unique_lock<std::mutex> &lock,
                     std::atomic<int> *progress_ptr) const {
  // Input: planar RGBA float Mat with values 0-255 from from_pixels
  // inimage has dims=3, w=width, h=height, c=4 (RGBA)

  int orig_w = inimage.w;
  int orig_h = inimage.h;

  // Use original resolution for full quality per user request
  const ncnn::Mat &work_img = inimage;

  int w = work_img.w;
  int h = work_img.h;
  int target_w = w * scale;
  int target_h = h * scale;

  LOGD("Processing image %dx%d (orig %dx%d) -> %dx%d", w, h, orig_w, orig_h,
       target_w, target_h);

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

  if (disable_grayscale_check) {
    is_grayscale = false;
  }

  if (is_grayscale)
    LOGD("Grayscale image detected, forcing pure grayscale output.");

  // PRE-PROCESS ALPHA CHANNEL (moved to start)
  // We need full alpha map to merge tiles on the fly
  ncnn::Mat alpha_out;
  bool has_alpha = (inimage.c >= 4);
  const float *alpha_data = nullptr;

  if (has_alpha) {
    ncnn::Mat alpha_in = inimage.channel_range(3, 1);
    if (scale == 2) {
      bicubic_2x->forward(alpha_in, alpha_out, net.opt);
    } else {
      // For 3x/4x, use ncnn resize as fallback for alpha
      ncnn::resize_bilinear(alpha_in, alpha_out, target_w, target_h, net.opt);
    }
    alpha_data = (const float *)alpha_out.data;
  }

  // Tiling parameters
  const int TILE_SIZE_X = tilesize;
  const int TILE_SIZE_Y = tilesize;

  // Create padded input to handle borders easily
  ncnn::Mat padded_input;
  ncnn::copy_make_border(rgb_normalized, padded_input, prepadding, prepadding,
                         prepadding, prepadding, ncnn::BORDER_REPLICATE, 0.f,
                         net.opt);

  // NO huge model_out allocation needed anymore!

  const int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
  const int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;

  // Future for the background CPU conversion tasks (Buffered Pipeline)
  // Depth 32 allows GPU to run way ahead of CPU.
  std::deque<std::future<void>> pipeline;

  for (int yi = 0; yi < ytiles; yi++) {
    for (int xi = 0; xi < xtiles; xi++) {
      int x = xi * TILE_SIZE_X;
      int y = yi * TILE_SIZE_Y;

      int w_tile = std::min(TILE_SIZE_X, w - x);
      int h_tile = std::min(TILE_SIZE_Y, h - y);

      int in_tile_w = w_tile + 2 * prepadding;
      int in_tile_h = h_tile + 2 * prepadding;

      // Extract tile from padded_input
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

      // Run inference on tile (GPU WORK)
      ncnn::Mat out_tile;
      {
        ncnn::Extractor ex = net.create_extractor();
        ex.set_light_mode(true);
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
        continue;
      }

      // Update progress IMMEDIATELY after GPU inference to show activity
      if (progress_ptr) {
        int p =
            (xi + yi * xtiles) * 99 / (xtiles * ytiles) + 1; // Slight offset
        progress_ptr->store(p);
      }

      // ---------------------------------------------------------
      // BUFFERED PIPELINE MANAGEMENT
      // ---------------------------------------------------------
      // Wait for the oldest task if the pipeline is full
      while (pipeline.size() >= 32) {
        pipeline.front().wait();
        pipeline.pop_front();
      }

      // Capture by value [=] ensures all local variables needed for conversion
      // are copied. ncnn::Mat out_tile is ref-counted, so copy is fast.
      pipeline.push_back(
          std::async(std::launch::async, [=, out_tile_captured = out_tile]() {
            int out_x = x * scale;
            int out_y = y * scale;
            int out_w_tile = w_tile * scale;
            int out_h_tile = h_tile * scale;
            int out_pad = prepadding * scale;

            int src_offset_x = out_pad;
            int src_offset_y = out_pad;

            if (out_tile_captured.w < out_w_tile + 2 * out_pad ||
                out_tile_captured.h < out_h_tile + 2 * out_pad) {
              src_offset_x = 0;
              src_offset_y = 0;
            }

            const float *tile_b = out_tile_captured.channel(0);
            const float *tile_g = out_tile_captured.channel(1);
            const float *tile_r = out_tile_captured.channel(2);

            // Iterate over valid output rows for this tile
            for (int i = 0; i < out_h_tile; i++) {
              int dst_y = out_y + i;
              int src_y = src_offset_y + i;

              if (dst_y >= target_h)
                break;
              if (src_y >= out_tile_captured.h)
                break;

              unsigned char *dst_row =
                  (unsigned char *)out_pixels + dst_y * out_stride;

              // Pointers into the tile data
              int src_row_offset = src_y * out_tile_captured.w;
              const float *ptr_b = tile_b + src_row_offset + src_offset_x;
              const float *ptr_g = tile_g + src_row_offset + src_offset_x;
              const float *ptr_r = tile_r + src_row_offset + src_offset_x;

              // Pointer into global alpha data
              const float *ptr_a = nullptr;
              if (alpha_data) {
                ptr_a = alpha_data + dst_y * target_w + out_x;
              }

              int copy_w = out_w_tile;
              if (out_x + copy_w > target_w)
                copy_w = target_w - out_x;
              if (src_offset_x + copy_w > out_tile_captured.w)
                copy_w = out_tile_captured.w - src_offset_x;

#pragma omp parallel for num_threads(2) if (copy_w > 512)
              for (int j = 0; j < copy_w; j++) {
                float r = ptr_r[j] * 255.0f;
                float g = ptr_g[j] * 255.0f;
                float b = ptr_b[j] * 255.0f;

                if (is_grayscale) {
                  float gray = (r + g + b) * 0.333333f;
                  r = g = b = gray;
                }

                int dst_x = out_x + j;
                int dst_idx = dst_x * 4;

                dst_row[dst_idx + 0] =
                    (unsigned char)std::max(0.0f, std::min(255.0f, r));
                dst_row[dst_idx + 1] =
                    (unsigned char)std::max(0.0f, std::min(255.0f, g));
                dst_row[dst_idx + 2] =
                    (unsigned char)std::max(0.0f, std::min(255.0f, b));
                dst_row[dst_idx + 3] =
                    ptr_a ? (unsigned char)(ptr_a[j] * 255.0f) : 255;
              }
            }

            // Update progress after this tile is fully written to UI
            if (progress_ptr) {
              int p = (xi + yi * xtiles + 1) * 99 / (xtiles * ytiles);
              progress_ptr->store(p);
            }
          }));

      // Check for abort signal
      if (should_abort_ptr && should_abort_ptr->load()) {
        LOGD("Waifu2x process aborted by signal");
        return -1;
      }

      // Skip sleep for the last few tiles
      bool is_near_end = (xi + yi * xtiles) > (xtiles * ytiles - 5);
      if (tile_sleep_ms > 0 && !is_near_end) {
        std::this_thread::sleep_for(std::chrono::milliseconds(tile_sleep_ms));
      }
    }
  }

  // ---------------------------------------------------------
  // GPU WORK DONE - RELEASE LOCK EARLY!
  // ---------------------------------------------------------
  // All GPU inference tasks are submitted. Releasing the lock allows the NEXT
  // image to start its GPU work while we finish CPU conversion for the current
  // image's buffered tiles.
  LOGD("GPU work finished, releasing lock early for next image.");
  lock.unlock();

  // Wait for all remaining tile conversions in the pipeline
  while (!pipeline.empty()) {
    pipeline.front().wait();
    pipeline.pop_front();
  }

  if (progress_ptr) {
    progress_ptr->store(100);
  }

  LOGD("Processing complete: %dx%d (Native side finished)", target_w, target_h);

  return 0;
}
