#include "spatial_qnn_runtime.h"
#include <android/log.h>
#include <jni.h>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <exception>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <sys/resource.h>
#include <sys/stat.h>

namespace {
using Clock = std::chrono::steady_clock;
double millis(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
std::string utf(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}
void stage(JNIEnv* env, jobject activity, const char* name) {
    __android_log_print(ANDROID_LOG_INFO, "NpuBench", "%s", name);
    jmethodID method = env->GetMethodID(env->GetObjectClass(activity), "onNativeStage", "(Ljava/lang/String;)V");
    jstring text = env->NewStringUTF(name);
    env->CallVoidMethod(activity, method, text);
    env->DeleteLocalRef(text);
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_mihon_npubench_MainActivity_nativeRun(JNIEnv* env, jobject activity,
    jstring modelPath, jstring contextPath, jstring libraryDir, jboolean cold) {
    std::ostringstream report;
    report << std::fixed << std::setprecision(1);
    try {
        const std::string dlc = utf(env, modelPath);
        const std::string binary = utf(env, contextPath);
        const std::string dsp = utf(env, libraryDir) +
            ";/vendor/dsp/cdsp;/vendor/lib/rfsa/cdsp;/system/vendor/lib/rfsa/cdsp";
        setenv("ADSP_LIBRARY_PATH", dsp.c_str(), 1);
        spatial_qnn::Runtime runtime;
        stage(env, activity, "Initializing QNN 2.49 HTP runtime");
        auto start = Clock::now();
        if (!runtime.initialize()) throw std::runtime_error(runtime.error());
        report << "QNN initialization: " << millis(start) << " ms\n";
        if (cold) {
            stage(env, activity, "Compiling FP16 DLC on this device (compose + finalize + serialize)");
            start = Clock::now();
            if (!runtime.compile_dlc(dlc, binary)) throw std::runtime_error(runtime.error());
            report << "CONTEXT COMPILE: " << millis(start) << " ms\n";
        } else {
            report << "Compile skipped: loading saved context\n";
        }
        struct stat info{};
        if (stat(binary.c_str(), &info) != 0 || info.st_size <= 0)
            throw std::runtime_error("Missing context file; run cold compile first");
        report << "Context size: " << info.st_size << " bytes\n";
        // Recreate the runtime so load timing does not reuse the compilation context.
        runtime.reset();
        stage(env, activity, "Reinitializing runtime for saved context validation");
        if (!runtime.initialize()) throw std::runtime_error(runtime.error());
        stage(env, activity, "Loading generated context");
        start = Clock::now();
        if (!runtime.load(binary)) throw std::runtime_error(runtime.error());
        report << "CONTEXT LOAD: " << millis(start) << " ms\n";
        const auto& inputs = runtime.input_metadata();
        const auto& outputs = runtime.output_metadata();
        if (inputs.size() != 1 || outputs.size() != 1 ||
            inputs[0].dimensions != std::vector<uint32_t>{1, 256, 256, 3} ||
            outputs[0].dimensions != std::vector<uint32_t>{1, 512, 512, 3})
            throw std::runtime_error("Unexpected graph shape; expected NHWC 256 -> 512 RGB");
        stage(env, activity, "Running one synthetic 256x256 tile on HTP");
        spatial_qnn::TensorData input = inputs[0];
        input.values.resize(256 * 256 * 3);
        for (size_t y = 0; y < 256; ++y) {
            for (size_t x = 0; x < 256; ++x) {
                const size_t i = (y * 256 + x) * 3;
                input.values[i] = static_cast<float>(x) / 255;
                input.values[i + 1] = static_cast<float>(y) / 255;
                input.values[i + 2] = 0.5f;
            }
        }
        std::vector<spatial_qnn::TensorData> result;
        start = Clock::now();
        if (!runtime.execute({std::move(input)}, result)) throw std::runtime_error(runtime.error());
        report << "FIRST TILE (including tensor copies): " << millis(start) << " ms\n";
        if (result.size() != 1 || result[0].values.size() != 512 * 512 * 3)
            throw std::runtime_error("Unexpected inference output size");
        double sum = 0;
        for (float value : result[0].values) {
            if (!std::isfinite(value)) throw std::runtime_error("Non-finite inference output");
            sum += value;
        }
        report << "Output mean: " << sum / result[0].values.size() << "\n";
        report << "SUCCESS: context compiled/loaded and executed on HTP\n";
    } catch (const std::exception& error) {
        report << "FAILED: " << error.what() << "\n";
        __android_log_print(ANDROID_LOG_ERROR, "NpuBench", "%s", error.what());
    }
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    report << "Process peak RSS since launch: " << usage.ru_maxrss / 1024.0 << " MiB\n";
    return env->NewStringUTF(report.str().c_str());
}
