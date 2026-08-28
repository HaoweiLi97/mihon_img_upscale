#include "qnn_backend.h"
#include "spatial_qnn_runtime.h"

#include <android/log.h>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

namespace {
constexpr char kLogTag[] = "DepthQnn";
constexpr size_t kImageSize = 518;
constexpr size_t kInputElements = kImageSize * kImageSize * 3;
constexpr size_t kOutputElements = kImageSize * kImageSize;
std::mutex inference_mutex;
std::mutex error_mutex;
std::string last_error;

void set_error(const std::string &message) {
  {
    std::lock_guard<std::mutex> lock(error_mutex);
    last_error = message;
  }
  if (!message.empty()) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
  }
}

std::string copy_string(JNIEnv *env, jstring value) {
  if (!value) return {};
  const char *utf = env->GetStringUTFChars(value, nullptr);
  std::string result = utf ? utf : "";
  if (utf) env->ReleaseStringUTFChars(value, utf);
  return result;
}

void configure_dsp_library_path(JNIEnv *env, jstring native_library_dir) {
  const std::string library_dir = copy_string(env, native_library_dir);
  if (library_dir.empty()) return;
  const std::string dsp_paths =
      library_dir +
      ";/vendor/dsp/cdsp;/vendor/lib/rfsa/cdsp;/system/vendor/lib/rfsa/cdsp";
  setenv("ADSP_LIBRARY_PATH", dsp_paths.c_str(), 1);
}

bool has_dimensions(const spatial_qnn::TensorData &tensor,
                    const std::vector<uint32_t> &expected) {
  return tensor.dimensions == expected;
}

bool prepare_runtime(spatial_qnn::Runtime &runtime, const std::string &model_path,
                     const std::string &context_path) {
  if (runtime.compile_dlc(model_path, context_path) &&
      runtime.load(context_path)) {
    return true;
  }
  const std::string first_error = runtime.error();
  runtime.unload_graph();
  std::remove(context_path.c_str());
  if (!runtime.compile_dlc(model_path, context_path) ||
      !runtime.load(context_path)) {
    set_error(runtime.error().empty() ? first_error : runtime.error());
    return false;
  }
  return true;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_ui_reader_spatial_DepthQnnBridge_nativeIsRuntimeAvailable(
    JNIEnv *env, jobject, jstring native_library_dir) {
  configure_dsp_library_path(env, native_library_dir);
  if (!qnn_backend::is_runtime_loadable()) {
    set_error("QNN HTP runtime is unavailable");
    return JNI_FALSE;
  }
  spatial_qnn::Runtime runtime;
  if (!runtime.initialize()) {
    set_error(runtime.error());
    return JNI_FALSE;
  }
  set_error("");
  return JNI_TRUE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_eu_kanade_tachiyomi_ui_reader_spatial_DepthQnnBridge_nativeInferDepth(
    JNIEnv *env, jobject, jfloatArray input, jstring model_path,
    jstring context_path, jstring native_library_dir) {
  std::lock_guard<std::mutex> inference_lock(inference_mutex);
  configure_dsp_library_path(env, native_library_dir);
  if (!input || static_cast<size_t>(env->GetArrayLength(input)) !=
                    kInputElements) {
    set_error("Depth Anything V3 requires a 1x518x518x3 input");
    return nullptr;
  }

  spatial_qnn::Runtime runtime;
  if (!prepare_runtime(runtime, copy_string(env, model_path),
                       copy_string(env, context_path))) {
    return nullptr;
  }
  if (runtime.input_metadata().size() != 1 ||
      runtime.output_metadata().size() != 1 ||
      !has_dimensions(runtime.input_metadata()[0], {1, 518, 518, 3}) ||
      !has_dimensions(runtime.output_metadata()[0], {1, 518, 518, 1})) {
    set_error("Unexpected Depth Anything V3 tensor layout");
    return nullptr;
  }

  spatial_qnn::TensorData model_input = runtime.input_metadata()[0];
  model_input.values.resize(kInputElements);
  env->GetFloatArrayRegion(input, 0, static_cast<jsize>(kInputElements),
                           model_input.values.data());
  if (env->ExceptionCheck()) {
    set_error("Unable to read the depth model input");
    return nullptr;
  }

  std::vector<spatial_qnn::TensorData> outputs;
  if (!runtime.execute({std::move(model_input)}, outputs) ||
      outputs.size() != 1 || outputs[0].values.size() != kOutputElements) {
    set_error(runtime.error().empty() ? "Depth QNN execution failed"
                                      : runtime.error());
    return nullptr;
  }

  jfloatArray result = env->NewFloatArray(static_cast<jsize>(kOutputElements));
  if (!result) {
    set_error("Unable to allocate the depth result");
    return nullptr;
  }
  env->SetFloatArrayRegion(result, 0, static_cast<jsize>(kOutputElements),
                           outputs[0].values.data());
  if (env->ExceptionCheck()) {
    set_error("Unable to return the depth result");
    return nullptr;
  }
  set_error("");
  return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_eu_kanade_tachiyomi_ui_reader_spatial_DepthQnnBridge_nativeInferDepthBatch(
    JNIEnv *env, jobject, jfloatArray inputs, jint batch_count,
    jstring model_path, jstring context_path, jstring native_library_dir) {
  std::lock_guard<std::mutex> inference_lock(inference_mutex);
  configure_dsp_library_path(env, native_library_dir);
  if (batch_count < 1 || batch_count > 4 || !inputs ||
      static_cast<size_t>(env->GetArrayLength(inputs)) !=
          kInputElements * static_cast<size_t>(batch_count)) {
    set_error("Depth Anything V3 high-resolution batch is invalid");
    return nullptr;
  }

  spatial_qnn::Runtime runtime;
  if (!prepare_runtime(runtime, copy_string(env, model_path),
                       copy_string(env, context_path))) {
    return nullptr;
  }
  if (runtime.input_metadata().size() != 1 ||
      runtime.output_metadata().size() != 1 ||
      !has_dimensions(runtime.input_metadata()[0], {1, 518, 518, 3}) ||
      !has_dimensions(runtime.output_metadata()[0], {1, 518, 518, 1})) {
    set_error("Unexpected Depth Anything V3 tensor layout");
    return nullptr;
  }

  std::vector<float> combined_outputs(
      kOutputElements * static_cast<size_t>(batch_count));
  for (jint batch = 0; batch < batch_count; ++batch) {
    spatial_qnn::TensorData model_input = runtime.input_metadata()[0];
    model_input.values.resize(kInputElements);
    env->GetFloatArrayRegion(
        inputs, static_cast<jsize>(static_cast<size_t>(batch) * kInputElements),
        static_cast<jsize>(kInputElements), model_input.values.data());
    if (env->ExceptionCheck()) {
      set_error("Unable to read a high-resolution depth tile");
      return nullptr;
    }

    std::vector<spatial_qnn::TensorData> outputs;
    if (!runtime.execute({std::move(model_input)}, outputs) ||
        outputs.size() != 1 || outputs[0].values.size() != kOutputElements) {
      set_error(runtime.error().empty() ? "Depth QNN tile execution failed"
                                        : runtime.error());
      return nullptr;
    }
    std::copy(outputs[0].values.begin(), outputs[0].values.end(),
              combined_outputs.begin() +
                  static_cast<size_t>(batch) * kOutputElements);
  }

  jfloatArray result = env->NewFloatArray(static_cast<jsize>(combined_outputs.size()));
  if (!result) {
    set_error("Unable to allocate the high-resolution depth result");
    return nullptr;
  }
  env->SetFloatArrayRegion(result, 0, static_cast<jsize>(combined_outputs.size()),
                           combined_outputs.data());
  if (env->ExceptionCheck()) {
    set_error("Unable to return the high-resolution depth result");
    return nullptr;
  }
  set_error("");
  return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_eu_kanade_tachiyomi_ui_reader_spatial_DepthQnnBridge_nativeLastError(
    JNIEnv *env, jobject) {
  std::lock_guard<std::mutex> lock(error_mutex);
  return env->NewStringUTF(last_error.c_str());
}
