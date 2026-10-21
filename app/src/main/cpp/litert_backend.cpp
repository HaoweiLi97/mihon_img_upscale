#include "litert_backend.h"
#include "float_tile_processor.h"
#include <litert/c/litert_compiled_model.h>
#include <litert/c/litert_environment.h>
#include <litert/c/litert_model.h>
#include <litert/c/litert_options.h>
#include <litert/c/litert_tensor_buffer.h>
#include <android/log.h>
#include <cstring>
#include <dlfcn.h>

namespace litert_backend {
namespace {
#define API_FUNCTIONS(X) \
  X(LiteRtCreateEnvironment) X(LiteRtDestroyEnvironment) \
  X(LiteRtCreateOptions) X(LiteRtDestroyOptions) X(LiteRtSetOptionsHardwareAccelerators) \
  X(LiteRtCreateModelFromFile) X(LiteRtDestroyModel) X(LiteRtGetModelSignature) \
  X(LiteRtGetNumSignatureInputs) X(LiteRtGetNumSignatureOutputs) \
  X(LiteRtGetSignatureInputTensorByIndex) X(LiteRtGetSignatureOutputTensorByIndex) \
  X(LiteRtGetRankedTensorType) X(LiteRtCreateCompiledModel) X(LiteRtDestroyCompiledModel) \
  X(LiteRtCompiledModelIsFullyAccelerated) X(LiteRtGetCompiledModelInputBufferRequirements) \
  X(LiteRtGetCompiledModelOutputBufferRequirements) X(LiteRtCreateManagedTensorBufferFromRequirements) \
  X(LiteRtDestroyTensorBuffer) X(LiteRtLockTensorBuffer) X(LiteRtUnlockTensorBuffer) \
  X(LiteRtRunCompiledModel)

struct Runtime {
  void *library = nullptr;
#define DECLARE(name) decltype(&name) name##_ = nullptr;
  API_FUNCTIONS(DECLARE)
#undef DECLARE
  LiteRtEnvironment environment = nullptr;
  LiteRtOptions options = nullptr;
  LiteRtModel model = nullptr;
  LiteRtCompiledModel compiled = nullptr;
  LiteRtTensorBuffer input = nullptr, output = nullptr;
  LiteRtRankedTensorType input_type{}, output_type{};
  int scale = 2, padding = 18;
  bool npu = false;
  std::string key, error, library_directory, cache_directory;

  bool fail(const std::string &message) {
    error = message;
    __android_log_print(ANDROID_LOG_ERROR, "LiteRtBackend", "%s", message.c_str());
    return false;
  }
  bool okay(LiteRtStatus status, const char *stage) {
    return status == kLiteRtStatusOk || fail(std::string(stage) + ": " + std::to_string(status));
  }
  void close() {
    if (input) LiteRtDestroyTensorBuffer_(input);
    if (output) LiteRtDestroyTensorBuffer_(output);
    if (compiled) LiteRtDestroyCompiledModel_(compiled);
    if (model) LiteRtDestroyModel_(model);
    if (options) LiteRtDestroyOptions_(options);
    if (environment) LiteRtDestroyEnvironment_(environment);
    input = output = nullptr;
    compiled = nullptr; model = nullptr; options = nullptr; environment = nullptr;
    npu = false; key.clear();
    if (library) dlclose(library);
    library = nullptr;
  }
  bool check_type(const LiteRtRankedTensorType &type, int side) {
    const auto &l = type.layout;
    return type.element_type == kLiteRtElementTypeFloat32 && l.rank == 4 &&
        !l.has_strides && l.dimensions[0] == 1 && l.dimensions[1] == side &&
        l.dimensions[2] == side && l.dimensions[3] == 3;
  }
  bool load(const std::string &path, const std::string &cache, const std::string &libraries,
            int output_scale, int border, bool cpu_test) {
    std::string identity = path + "|" + cache + "|" + libraries + "|" + std::to_string(output_scale) + "|" + std::to_string(border) + (cpu_test ? "|cpu-test" : "|npu");
    if (compiled && key == identity) return true;
    close(); error.clear();
    if (output_scale != 2 && output_scale != 3) return fail("Unsupported model scale");
    library = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return fail("Unable to load LiteRT runtime");
#define LOAD(name) name##_ = reinterpret_cast<decltype(name##_)>(dlsym(library, #name)); \
    if (!name##_) return fail("Missing LiteRT API: " #name);
    API_FUNCTIONS(LOAD)
#undef LOAD
    LiteRtEnvOption env_options[3]{};
    const LiteRtEnvOptionTag tags[] = {kLiteRtEnvOptionTagCompilerPluginLibraryDir,
        kLiteRtEnvOptionTagDispatchLibraryDir, kLiteRtEnvOptionTagCompilerCacheDir};
    library_directory = libraries; cache_directory = cache;
    const char *values[] = {library_directory.c_str(), library_directory.c_str(), cache_directory.c_str()};
    for (int i = 0; i < 3; ++i) {
      env_options[i].tag = tags[i];
      env_options[i].value.type = kLiteRtAnyTypeString;
      env_options[i].value.str_value = values[i];
    }
    if (!okay(LiteRtCreateEnvironment_(3, env_options, &environment), "Create environment") ||
        !okay(LiteRtCreateOptions_(&options), "Create options") ||
        !okay(LiteRtSetOptionsHardwareAccelerators_(options, cpu_test ?
            kLiteRtHwAcceleratorCpu : kLiteRtHwAcceleratorNpu), "Select accelerator") ||
        !okay(LiteRtCreateModelFromFile_(environment, path.c_str(), &model), "Load model")) return false;
    LiteRtSignature signature = nullptr;
    LiteRtTensor in_tensor = nullptr, out_tensor = nullptr;
    LiteRtParamIndex input_count = 0, output_count = 0;
    if (!okay(LiteRtGetModelSignature_(model, 0, &signature), "Read signature") ||
        !okay(LiteRtGetNumSignatureInputs_(signature, &input_count), "Count inputs") ||
        !okay(LiteRtGetNumSignatureOutputs_(signature, &output_count), "Count outputs")) return false;
    if (input_count != 1 || output_count != 1) return fail("Expected one input and output");
    if (!okay(LiteRtGetSignatureInputTensorByIndex_(signature, 0, &in_tensor), "Input tensor") ||
        !okay(LiteRtGetSignatureOutputTensorByIndex_(signature, 0, &out_tensor), "Output tensor") ||
        !okay(LiteRtGetRankedTensorType_(in_tensor, &input_type), "Input type") ||
        !okay(LiteRtGetRankedTensorType_(out_tensor, &output_type), "Output type")) return false;
    if (!check_type(input_type, 256) || !check_type(output_type, 256 * output_scale))
      return fail("Expected contiguous FP32 NHWC RGB tensors, 256 to 512/768");
    if (!okay(LiteRtCreateCompiledModel_(environment, model, options, &compiled), "Compile model")) return false;
    bool fully_accelerated = false;
    if (!okay(LiteRtCompiledModelIsFullyAccelerated_(compiled, &fully_accelerated), "Check delegation")) return false;
    // Request NPU only and reject any CPU/partial delegation. Never label a CPU model as NPU.
    if (!cpu_test && !fully_accelerated) {
      LiteRtDestroyCompiledModel_(compiled); compiled = nullptr;
      return fail("The model could not be fully delegated to MediaTek NPU; enhancement skipped");
    }
    LiteRtTensorBufferRequirements in_requirements = nullptr, out_requirements = nullptr;
    if (!okay(LiteRtGetCompiledModelInputBufferRequirements_(compiled, 0, 0, &in_requirements), "Input requirements") ||
        !okay(LiteRtGetCompiledModelOutputBufferRequirements_(compiled, 0, 0, &out_requirements), "Output requirements") ||
        !okay(LiteRtCreateManagedTensorBufferFromRequirements_(environment, &input_type, in_requirements, &input), "Input buffer") ||
        !okay(LiteRtCreateManagedTensorBufferFromRequirements_(environment, &output_type, out_requirements, &output), "Output buffer")) return false;
    key = identity; scale = output_scale; padding = border; npu = !cpu_test;
    __android_log_print(ANDROID_LOG_INFO, "LiteRtBackend", "Ready: %s (%s)", path.c_str(),
                        npu ? "fully delegated NPU" : "CPU validation only");
    return true;
  }
  bool run(const std::vector<float> &data, std::vector<float> &result) {
    if (!compiled || !input || !output || data.size() != 256*256*3) return fail("Model is not ready");
    void *memory = nullptr;
    if (!okay(LiteRtLockTensorBuffer_(input, &memory, kLiteRtTensorBufferLockModeWrite), "Lock input")) return false;
    std::memcpy(memory, data.data(), data.size() * sizeof(float));
    if (!okay(LiteRtUnlockTensorBuffer_(input), "Unlock input") ||
        !okay(LiteRtRunCompiledModel_(compiled, 0, 1, &input, 1, &output), "Execute model")) return false;
    if (!okay(LiteRtLockTensorBuffer_(output, &memory, kLiteRtTensorBufferLockModeRead), "Lock output")) return false;
    result.resize(static_cast<size_t>(256 * scale) * 256 * scale * 3);
    std::memcpy(result.data(), memory, result.size() * sizeof(float));
    return okay(LiteRtUnlockTensorBuffer_(output), "Unlock output");
  }
};
Runtime runtime;
} // namespace

bool initialize(const std::string &model, const std::string &cache, const std::string &libraries,
                int scale, int padding, bool cpu_test_only) {
  if (runtime.load(model, cache, libraries, scale, padding, cpu_test_only)) return true;
  runtime.close();
  return false;
}
std::string prepare(const std::string &model, const std::string &cache, const std::string &libraries,
                    int scale, int padding) {
  Runtime compiler;
  const bool ready = compiler.load(model, cache, libraries, scale, padding, false);
  const std::string error = ready ? "" : compiler.error;
  compiler.close();
  return error;
}
bool is_initialized() { return runtime.compiled && runtime.input && runtime.output; }
bool is_npu_active() { return is_initialized() && runtime.npu; }
std::string last_error() { return runtime.error; }
bool run_tile(const std::vector<float> &input, std::vector<float> &output) { return runtime.run(input, output); }
int process_rgba(const uint8_t *input, int width, int height, int input_stride,
                 uint8_t *output, int output_stride, periodic_texture_guard::Analysis &analysis,
                 std::atomic<int> *progress, const std::atomic<bool> *abort) {
  if (!is_initialized()) return -1;
  return float_tiles::process(input, width, height, input_stride, output, output_stride,
      runtime.scale, runtime.padding, analysis, progress, abort,
      [](const std::vector<float> &in, std::vector<float> &out) { return runtime.run(in, out); });
}
void shutdown() { runtime.close(); }
} // namespace litert_backend
