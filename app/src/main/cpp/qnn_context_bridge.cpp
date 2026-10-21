#include "spatial_qnn_runtime.h"

#include <cstdlib>
#include <jni.h>
#include <mutex>
#include <string>

namespace {
std::mutex compile_mutex;
std::string utf(JNIEnv *env, jstring value) {
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string result = chars ? chars : "";
  if (chars) env->ReleaseStringUTFChars(value, chars);
  return result;
}
} // namespace

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
