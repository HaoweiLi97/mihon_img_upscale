#include "anime4k.h"
#include "waifu2x.h"
#include <android/bitmap.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <jni.h>
#include <mutex>
#include <vector>

#define TAG "Waifu2xJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static Waifu2x *g_waifu2x = nullptr;
static Anime4K *g_anime4k = nullptr;
static std::mutex g_lock;
static std::atomic<int> g_progress{0};
static std::atomic<int> g_current_id{-1};
static std::atomic<int> g_ui_busy{0};
static std::atomic<bool> g_abort_processing{false};

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInit(JNIEnv *env,
                                                         jobject thiz,
                                                         jstring model_dir,
                                                         jint noise_level,
                                                         jint scale_level) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;

  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  // Choose model files based on noise and scale
  std::string param_file;
  std::string bin_file;

  if (noise_level == -1) {
    param_file = model_path + "/scale2.0x_model.param";
    bin_file = model_path + "/scale2.0x_model.bin";
  } else if (scale_level == 1) {
    param_file =
        model_path + "/noise" + std::to_string(noise_level) + "_model.param";
    bin_file =
        model_path + "/noise" + std::to_string(noise_level) + "_model.bin";
  } else {
    param_file = model_path + "/noise" + std::to_string(noise_level) +
                 "_scale2.0x_model.param";
    bin_file = model_path + "/noise" + std::to_string(noise_level) +
               "_scale2.0x_model.bin";
  }

  g_waifu2x = new Waifu2x(0); // GPU 0
  g_waifu2x->disable_grayscale_check = true;
  g_waifu2x->noise = noise_level;
  g_waifu2x->scale = scale_level;
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  int ret = g_waifu2x->load(param_file, bin_file);

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitWaifu2xUpconv7(
    JNIEnv *env, jobject thiz, jstring model_dir, jint noise_level,
    jint scale_level) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;

  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  // UpConv7 naming: noise0_scale2.0x_model.param
  // noise: 0, 1, 2, 3
  // scale: 2 (only support 2x for now based on files seen)

  std::string param_file = model_path + "/noise" + std::to_string(noise_level) +
                           "_scale2.0x_model.param";
  std::string bin_file = model_path + "/noise" + std::to_string(noise_level) +
                         "_scale2.0x_model.bin";

  // Handle scale=2 only for now, or if scale=1 (denoise only)
  if (scale_level != 2) {
    // UpConv7 models I saw were all scale2.0x
    // If user selected 1x (denoise only), we might need to use a different
    // model or just fail/fallback For now assume 2x.
  }

  g_waifu2x = new Waifu2x(0); // GPU 0
  g_waifu2x->disable_grayscale_check = true;
  g_waifu2x->noise = noise_level;
  g_waifu2x->scale = scale_level;
  g_waifu2x->prepadding = 7; // UpConv7 uses small padding
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  int ret = g_waifu2x->load(param_file, bin_file);

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeProcess(JNIEnv *env,
                                                            jobject thiz,
                                                            jobject bitmap,
                                                            jint id) {
  int ret = -1;
  jobject outBitmap = nullptr;

  // Inference Scope (GPU) - Holds Lock for entire duration of incremental
  // process
  {
    std::unique_lock<std::mutex> lock(g_lock);

    // Update ID only after acquiring lock (now we are truly the active process)
    g_current_id.store(id);

    if (!g_waifu2x)
      return bitmap;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0)
      return bitmap;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
      return bitmap;

    void *pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0)
      return bitmap;

    int w = info.width;
    int h = info.height;
    int stride = info.stride;

    // Use from_pixels for input (copy to ncnn Mat)
    ncnn::Mat in = ncnn::Mat::from_pixels((const unsigned char *)pixels,
                                          ncnn::Mat::PIXEL_RGBA, w, h, stride);

    // Unlock input quickly if possible, checking if creating output needs it
    // locked? No, createBitmap doesn't need input locked. But we need to keep
    // 'pixels' valid if we didn't copy... ncnn::Mat::from_pixels COPIES data,
    // so we can unlock input immediately.
    AndroidBitmap_unlockPixels(env, bitmap);

    if (g_waifu2x) {
      int out_w = w * g_waifu2x->scale;
      int out_h = h * g_waifu2x->scale;

      // Create result bitmap
      jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
      jmethodID createBitmapMethod = env->GetStaticMethodID(
          bitmapClass, "createBitmap",
          "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");

      jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
      jfieldID configField = env->GetStaticFieldID(
          configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
      jobject config = env->GetStaticObjectField(configClass, configField);

      outBitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod,
                                              out_w, out_h, config);

      if (outBitmap) {
        void *outPixels;
        if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) == 0) {
          AndroidBitmapInfo outInfo;
          AndroidBitmap_getInfo(env, outBitmap, &outInfo);

          g_waifu2x->progress_ptr = &g_progress;
          g_waifu2x->should_abort_ptr = &g_abort_processing;

          // RUN UNIFIED PROCESS
          ret = g_waifu2x->process(in, outPixels, outInfo.stride, lock,
                                   &g_progress);

          g_waifu2x->progress_ptr = nullptr;
          g_waifu2x->should_abort_ptr = nullptr;

          AndroidBitmap_unlockPixels(env, outBitmap);
        }
      }
    }
  }

  if (ret != 0 || !outBitmap) {
    LOGE("Waifu2x process failed or aborted");
    return bitmap; // Return original on failure
  }

  return outBitmap;
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeDestroy(JNIEnv *env,
                                                            jobject thiz) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (g_waifu2x) {
    delete g_waifu2x;
    g_waifu2x = nullptr;
  }
  if (g_anime4k) {
    delete g_anime4k;
    g_anime4k = nullptr;
  }
  // DO NOT call destroy_gpu_instance here. It should be global.
  // Repeatedly calling it on exit/init is slow and can cause hangs.
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitAnime4K(
    JNIEnv *env, jobject thiz, jobjectArray shaders, jobjectArray names) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (g_anime4k)
    delete g_anime4k;
  g_anime4k = new Anime4K();

  std::vector<std::string> v_shaders;
  std::vector<std::string> v_names;
  jsize len = env->GetArrayLength(shaders);
  for (jsize i = 0; i < len; i++) {
    jstring s = (jstring)env->GetObjectArrayElement(shaders, i);
    jstring n = (jstring)env->GetObjectArrayElement(names, i);
    const char *cs = env->GetStringUTFChars(s, 0);
    const char *cn = env->GetStringUTFChars(n, 0);
    v_shaders.push_back(cs);
    v_names.push_back(cn);
    env->ReleaseStringUTFChars(s, cs);
    env->ReleaseStringUTFChars(n, cn);
  }

  int ret = g_anime4k->load(v_shaders, v_names);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeProcessAnime4K(
    JNIEnv *env, jobject thiz, jobject bitmap) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (!g_anime4k)
    return bitmap;

  AndroidBitmapInfo info;
  AndroidBitmap_getInfo(env, bitmap, &info);

  int out_w, out_h;
  g_anime4k->get_output_size(info.width, info.height, out_w, out_h);

  // Create result bitmap
  jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
  jmethodID createBitmapMethod = env->GetStaticMethodID(
      bitmapClass, "createBitmap",
      "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
  jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
  jfieldID configField = env->GetStaticFieldID(
      configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
  jobject config = env->GetStaticObjectField(configClass, configField);
  jobject outBitmap = env->CallStaticObjectMethod(
      bitmapClass, createBitmapMethod, out_w, out_h, config);

  if (!outBitmap) {
    LOGE("Failed to create output bitmap for Anime4K");
    return bitmap;
  }

  void *pixels;
  AndroidBitmap_lockPixels(env, bitmap, &pixels);
  void *outPixels;
  AndroidBitmap_lockPixels(env, outBitmap, &outPixels);

  int actual_out_w, actual_out_h;
  g_anime4k->process(info.width, info.height, (unsigned char *)pixels,
                     actual_out_w, actual_out_h, (unsigned char *)outPixels);

  AndroidBitmap_unlockPixels(env, bitmap);
  AndroidBitmap_unlockPixels(env, outBitmap);

  return outBitmap;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitRealCugan(
    JNIEnv *env, jobject thiz, jstring model_dir, jint noise_level,
    jint scale_level, jint tile_sleep_ms) {
  g_abort_processing = true; // Signal abort to any running process
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false; // Reset

  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  // Choose model files based on noise and scale
  // Noise mapping: 0: no-denoise, 1: denoise1x, 2: denoise2x, 3: denoise3x, 4:
  // conservative
  std::string noise_str;
  switch (noise_level) {
  case 0:
    noise_str = "no-denoise";
    break;
  case 1:
    noise_str = "denoise1x";
    break;
  case 2:
    noise_str = "denoise2x";
    break;
  case 3:
    noise_str = "denoise3x";
    break;
  case 4:
    noise_str = "conservative";
    break;
  default:
    noise_str = "no-denoise";
    break;
  }

  // Fallback for 3x/4x which only have no-denoise, denoise3x, conservative
  if (scale_level > 2 && noise_level > 0 && noise_level < 3) {
    noise_str = "denoise3x";
  }

  std::string param_file = model_path + "/up" + std::to_string(scale_level) +
                           "x-" + noise_str + ".param";
  std::string bin_file = model_path + "/up" + std::to_string(scale_level) +
                         "x-" + noise_str + ".bin";

  g_waifu2x = new Waifu2x(0); // GPU 0
  g_waifu2x->noise = noise_level;
  g_waifu2x->scale = scale_level;
  g_waifu2x->tile_sleep_ms = tile_sleep_ms; // Set configurable sleep
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  // Real-CUGAN SE prepadding: 2x=18, 3x=14, 4x=19?
  // Actually from official impl: 2x=18, 3x=14, 4x=19
  if (scale_level == 2)
    g_waifu2x->prepadding = 18;
  else if (scale_level == 3)
    g_waifu2x->prepadding = 14;
  else if (scale_level == 4)
    g_waifu2x->prepadding = 19;

  int ret = g_waifu2x->load(param_file, bin_file);

  if (ret != 0) {
    LOGE("Real-CUGAN load failed. ret=%d", ret);
    LOGE("Param path: %s", param_file.c_str());
    LOGE("Bin path: %s", bin_file.c_str());
  } else {
    LOGD("Real-CUGAN loaded successfully. Scale=%d, Noise=%d, TileSleep=%dms",
         scale_level, noise_level, tile_sleep_ms);
  }

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeProcessRealCugan(
    JNIEnv *env, jobject thiz, jobject bitmap, jint id) {
  // Real-CUGAN uses same processing logic as Waifu2x in this simplified impl
  return Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeProcess(
      env, thiz, bitmap, id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitRealESRGAN(
    JNIEnv *env, jobject thiz, jstring model_dir, jint scale) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;

  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  // Real-ESRGAN v3 anime uses x2, x3, x4 naming
  std::string param_file = model_path + "/x" + std::to_string(scale) + ".param";
  std::string bin_file = model_path + "/x" + std::to_string(scale) + ".bin";

  g_waifu2x = new Waifu2x(0); // GPU 0
  g_waifu2x->noise = 0;
  g_waifu2x->scale = scale;
  g_waifu2x->prepadding = 10; // Real-ESRGAN usually uses smaller padding, 10 is
                              // common in ncnn impls
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  int ret = g_waifu2x->load(param_file, bin_file);

  if (ret != 0) {
    LOGE("Real-ESRGAN init failed: %s", param_file.c_str());
  } else {
    LOGD("Real-ESRGAN loaded: x%d", scale);
  }

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitNose(
    JNIEnv *env, jobject thiz, jstring model_dir) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;

  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  // Nose (Real-CUGAN branch?) uses up2x-no-denoise
  std::string param_file = model_path + "/up2x-no-denoise.param";
  std::string bin_file = model_path + "/up2x-no-denoise.bin";

  g_waifu2x = new Waifu2x(0); // GPU 0
  g_waifu2x->noise = 0;
  g_waifu2x->scale = 2;       // Fixed 2x
  g_waifu2x->prepadding = 18; // Assumed 18 for CUGAN 2x
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  int ret = g_waifu2x->load(param_file, bin_file);

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeGetProgress(JNIEnv *env,
                                                                jobject thiz) {
  // Return packed long: [ID (32)] [Progress (32)]
  jlong id = (jlong)g_current_id.load();
  jlong progress = (jlong)g_progress.load();
  return (id << 32) | (progress & 0xFFFFFFFF);
}
extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeSetUiBusy(JNIEnv *env,
                                                              jobject thiz,
                                                              jboolean busy) {
  g_ui_busy.store(busy ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeUpdatePerformanceConfig(
    JNIEnv *env, jobject thiz, jint sleep_ms, jint tile_size) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (g_waifu2x) {
    g_waifu2x->tile_sleep_ms = sleep_ms;
    g_waifu2x->tilesize = tile_size;
    LOGD("Updated performance config: sleep=%dms, tilesize=%d", sleep_ms,
         tile_size);
  }
}
