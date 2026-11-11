#include "spatial_qnn_runtime.h"

#include <cstdlib>
#include <jni.h>
#include <mutex>
#include <string>
#if MIHON_ENABLE_QNN
#include "qnn_fp16_support.h"
#include <dlfcn.h>
#endif

namespace {
std::mutex compile_mutex;
std::string utf(JNIEnv *env, jstring value) {
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string result = chars ? chars : "";
  if (chars)
    env->ReleaseStringUTFChars(value, chars);
  return result;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_eu_kanade_tachiyomi_util_qnn_QnnModelContexts_nativeFp16Support(
    JNIEnv *env, jobject, jstring native_library_dir) {
  std::lock_guard<std::mutex> lock(compile_mutex);
  const std::string paths =
      utf(env, native_library_dir) +
      ";/vendor/dsp/cdsp;/vendor/lib/rfsa/cdsp;/system/vendor/lib/rfsa/cdsp";
  setenv("ADSP_LIBRARY_PATH", paths.c_str(), 1);
#if MIHON_ENABLE_QNN
  void *library = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_GLOBAL);
  if (!library)
    return -1;
  using Get = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);
  auto get = reinterpret_cast<Get>(dlsym(library, "QnnInterface_getProviders"));
  const QnnInterface_t **providers = nullptr;
  uint32_t count = 0;
  int result = -1;
  if (get && get(&providers, &count) == QNN_SUCCESS) {
    for (uint32_t i = 0; i < count; ++i) {
      const auto version = providers[i]->apiVersion.coreApiVersion;
      if (version.major != QNN_API_VERSION_MAJOR ||
          version.minor < QNN_API_VERSION_MINOR)
        continue;
      const auto &api = providers[i]->QNN_INTERFACE_VER_NAME;
      Qnn_BackendHandle_t backend = nullptr;
      Qnn_DeviceHandle_t device = nullptr;
      if (api.backendCreate(nullptr, nullptr, &backend) == QNN_SUCCESS) {
        const auto status = api.deviceCreate
                                ? api.deviceCreate(nullptr, nullptr, &device)
                                : QNN_SUCCESS;
        if (status == QNN_SUCCESS ||
            status == QNN_DEVICE_ERROR_UNSUPPORTED_FEATURE ||
            status == QNN_DEVICE_ERROR_INVALID_CONFIG)
          result = qnn_ondevice::fp16_support(*providers[i], backend);
        if (device && api.deviceFree)
          api.deviceFree(device);
        api.backendFree(backend);
      }
      break;
    }
  }
  dlclose(library);
  return result;
#else
  return -1;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_eu_kanade_tachiyomi_util_qnn_QnnModelContexts_nativeCompile(
    JNIEnv *env, jobject, jstring dlc_path, jstring context_path,
    jstring native_library_dir) {
  std::lock_guard<std::mutex> lock(compile_mutex);
  const std::string paths =
      utf(env, native_library_dir) +
      ";/vendor/dsp/cdsp;/vendor/lib/rfsa/cdsp;/system/vendor/lib/rfsa/cdsp";
  setenv("ADSP_LIBRARY_PATH", paths.c_str(), 1);
  spatial_qnn::Runtime runtime;
  if (!runtime.compile_dlc(utf(env, dlc_path), utf(env, context_path)) ||
      !runtime.load(utf(env, context_path))) {
    return env->NewStringUTF(runtime.error().c_str());
  }
  return env->NewStringUTF("");
}
