#include "spatial_qnn_runtime.h"

#include <algorithm>
#include <cerrno>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <limits>
#include <memory>
#include <new>
#include <sys/stat.h>
#include <utility>

#if MIHON_ENABLE_QNN
#include <QnnInterface.h>
#include <System/QnnSystemContext.h>
#include <System/QnnSystemInterface.h>
#endif

namespace spatial_qnn {

#if MIHON_ENABLE_QNN
namespace {

using GetProviders = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);
using GetSystemProviders =
    Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t ***, uint32_t *);

struct AlignedBytes {
  uint8_t *data = nullptr;
  size_t size = 0;

  AlignedBytes() = default;
  ~AlignedBytes() { std::free(data); }
  AlignedBytes(const AlignedBytes &) = delete;
  AlignedBytes &operator=(const AlignedBytes &) = delete;
  AlignedBytes(AlignedBytes &&other) noexcept
      : data(std::exchange(other.data, nullptr)),
        size(std::exchange(other.size, 0)) {}
  AlignedBytes &operator=(AlignedBytes &&other) noexcept {
    if (this != &other) {
      std::free(data);
      data = std::exchange(other.data, nullptr);
      size = std::exchange(other.size, 0);
    }
    return *this;
  }

  bool resize(size_t requested) {
    if (size >= requested && data) {
      std::memset(data, 0, requested);
      return true;
    }
    std::free(data);
    data = nullptr;
    size = 0;
    void *allocation = nullptr;
    if (requested != 0 && posix_memalign(&allocation, 4096, requested) != 0) {
      return false;
    }
    data = static_cast<uint8_t *>(allocation);
    size = requested;
    if (data) std::memset(data, 0, size);
    return true;
  }

  void clear() {
    std::free(data);
    data = nullptr;
    size = 0;
  }
};

struct OwnedTensor {
  Qnn_Tensor_t tensor = QNN_TENSOR_INIT;
  std::string name;
  std::vector<uint32_t> dimensions;
  AlignedBytes buffer;
};

uint16_t float_to_half(float value) {
  uint32_t bits = 0;
  std::memcpy(&bits, &value, sizeof(bits));
  const uint32_t sign = (bits >> 16u) & 0x8000u;
  int exponent = static_cast<int>((bits >> 23u) & 0xffu) - 127 + 15;
  uint32_t mantissa = bits & 0x7fffffu;
  if (exponent <= 0) {
    if (exponent < -10) return static_cast<uint16_t>(sign);
    mantissa = (mantissa | 0x800000u) >> (1 - exponent);
    return static_cast<uint16_t>(sign | ((mantissa + 0x1000u) >> 13u));
  }
  if (exponent >= 31) return static_cast<uint16_t>(sign | 0x7c00u);
  return static_cast<uint16_t>(sign | (static_cast<uint32_t>(exponent) << 10u) |
                               ((mantissa + 0x1000u) >> 13u));
}

float half_to_float(uint16_t value) {
  const uint32_t sign = static_cast<uint32_t>(value & 0x8000u) << 16u;
  uint32_t exponent = (value >> 10u) & 0x1fu;
  uint32_t mantissa = value & 0x3ffu;
  uint32_t bits = 0;
  if (exponent == 0) {
    if (mantissa == 0) {
      bits = sign;
    } else {
      int shift = 0;
      while ((mantissa & 0x400u) == 0) {
        mantissa <<= 1u;
        ++shift;
      }
      mantissa &= 0x3ffu;
      bits = sign | (static_cast<uint32_t>(112 - shift) << 23u) |
             (mantissa << 13u);
    }
  } else if (exponent == 31) {
    bits = sign | 0x7f800000u | (mantissa << 13u);
  } else {
    bits = sign | ((exponent + 112u) << 23u) | (mantissa << 13u);
  }
  float result = 0.0f;
  std::memcpy(&result, &bits, sizeof(result));
  return result;
}

bool checked_elements(const std::vector<uint32_t> &dimensions, size_t &count) {
  count = 1;
  if (dimensions.empty()) return false;
  for (uint32_t dimension : dimensions) {
    if (dimension == 0 || count > std::numeric_limits<size_t>::max() / dimension) {
      return false;
    }
    count *= dimension;
  }
  return true;
}

bool scale_offset(const Qnn_Tensor_t &tensor, float &scale, int32_t &offset) {
  const auto &quant = tensor.v2.quantizeParams;
  if (quant.encodingDefinition != QNN_DEFINITION_DEFINED ||
      quant.quantizationEncoding != QNN_QUANTIZATION_ENCODING_SCALE_OFFSET ||
      quant.scaleOffsetEncoding.scale <= 0.0f) {
    return false;
  }
  scale = quant.scaleOffsetEncoding.scale;
  offset = quant.scaleOffsetEncoding.offset;
  return true;
}

size_t element_size(Qnn_DataType_t type) {
  switch (type) {
  case QNN_DATATYPE_FLOAT_32:
  case QNN_DATATYPE_INT_32:
  case QNN_DATATYPE_UINT_32:
    return 4;
  case QNN_DATATYPE_FLOAT_16:
  case QNN_DATATYPE_UFIXED_POINT_16:
  case QNN_DATATYPE_SFIXED_POINT_16:
  case QNN_DATATYPE_INT_16:
  case QNN_DATATYPE_UINT_16:
    return 2;
  case QNN_DATATYPE_UFIXED_POINT_8:
  case QNN_DATATYPE_SFIXED_POINT_8:
  case QNN_DATATYPE_INT_8:
  case QNN_DATATYPE_UINT_8:
  case QNN_DATATYPE_BOOL_8:
    return 1;
  default:
    return 0;
  }
}

bool copy_tensor(const Qnn_Tensor_t &source, OwnedTensor &destination) {
  if (source.version != QNN_TENSOR_VERSION_2 || !source.v2.name ||
      !source.v2.dimensions || source.v2.rank == 0) {
    return false;
  }
  destination.tensor = source;
  destination.name = source.v2.name;
  destination.dimensions.assign(source.v2.dimensions,
                                source.v2.dimensions + source.v2.rank);
  destination.tensor.v2.name = destination.name.c_str();
  destination.tensor.v2.dimensions = destination.dimensions.data();
  destination.tensor.v2.isDynamicDimensions = nullptr;
  destination.tensor.v2.memType = QNN_TENSORMEMTYPE_RAW;
  destination.tensor.v2.clientBuf = {};
  return element_size(source.v2.dataType) != 0;
}

void free_graph_infos(QnnSystemContext_GraphInfo_t *graphs, uint32_t count) {
  if (!graphs) return;
  for (uint32_t i = 0; i < count; ++i) {
    if (graphs[i].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
      std::free(const_cast<char *>(graphs[i].graphInfoV1.graphName));
      std::free(graphs[i].graphInfoV1.graphInputs);
      std::free(graphs[i].graphInfoV1.graphOutputs);
    } else if (graphs[i].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
      std::free(const_cast<char *>(graphs[i].graphInfoV2.graphName));
      std::free(graphs[i].graphInfoV2.graphInputs);
      std::free(graphs[i].graphInfoV2.graphOutputs);
    } else if (graphs[i].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
      std::free(const_cast<char *>(graphs[i].graphInfoV3.graphName));
      std::free(graphs[i].graphInfoV3.graphInputs);
      std::free(graphs[i].graphInfoV3.graphOutputs);
    }
  }
  std::free(graphs);
}

bool graph_fields(const QnnSystemContext_GraphInfo_t &graph, const char *&name,
                  const Qnn_Tensor_t *&inputs, uint32_t &input_count,
                  const Qnn_Tensor_t *&outputs, uint32_t &output_count) {
  switch (graph.version) {
  case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
    name = graph.graphInfoV1.graphName;
    inputs = graph.graphInfoV1.graphInputs;
    input_count = graph.graphInfoV1.numGraphInputs;
    outputs = graph.graphInfoV1.graphOutputs;
    output_count = graph.graphInfoV1.numGraphOutputs;
    return true;
  case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
    name = graph.graphInfoV2.graphName;
    inputs = graph.graphInfoV2.graphInputs;
    input_count = graph.graphInfoV2.numGraphInputs;
    outputs = graph.graphInfoV2.graphOutputs;
    output_count = graph.graphInfoV2.numGraphOutputs;
    return true;
  case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
    name = graph.graphInfoV3.graphName;
    inputs = graph.graphInfoV3.graphInputs;
    input_count = graph.graphInfoV3.numGraphInputs;
    outputs = graph.graphInfoV3.graphOutputs;
    output_count = graph.graphInfoV3.numGraphOutputs;
    return true;
  default:
    return false;
  }
}

bool binary_graphs(const QnnSystemContext_BinaryInfo_t *info,
                   const QnnSystemContext_GraphInfo_t *&graphs,
                   uint32_t &count) {
  if (!info) return false;
  switch (info->version) {
  case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
    graphs = info->contextBinaryInfoV1.graphs;
    count = info->contextBinaryInfoV1.numGraphs;
    return true;
  case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
    graphs = info->contextBinaryInfoV2.graphs;
    count = info->contextBinaryInfoV2.numGraphs;
    return true;
  case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
    graphs = info->contextBinaryInfoV3.graphs;
    count = info->contextBinaryInfoV3.numGraphs;
    return true;
  default:
    return false;
  }
}

} // namespace
#endif

class Runtime::Impl {
public:
  std::string error;
  std::vector<TensorData> input_metadata;
  std::vector<TensorData> output_metadata;

#if MIHON_ENABLE_QNN
  void *dlc_library = nullptr;
  void *backend_library = nullptr;
  void *system_library = nullptr;
  const QnnInterface_t *provider = nullptr;
  const QnnSystemInterface_t *system_provider = nullptr;
  Qnn_BackendHandle_t backend = nullptr;
  Qnn_DeviceHandle_t device = nullptr;
  Qnn_ContextHandle_t context = nullptr;
  Qnn_GraphHandle_t graph = nullptr;
  std::string graph_name;
  std::vector<uint8_t> binary;
  std::vector<OwnedTensor> inputs;
  std::vector<OwnedTensor> outputs;

  bool fail(const std::string &message) {
    error = message;
    return false;
  }

  const auto &qnn() const {
    return provider->QNN_INTERFACE_VER_NAME;
  }

  const auto &system() const {
    return system_provider->QNN_SYSTEM_INTERFACE_VER_NAME;
  }

  bool initialize() {
    if (backend && provider && system_provider) return true;
    reset();
    // libQnnSystem resolves DLC composition through symbols exported by
    // libQnnModelDlc, so make the latter globally visible first.
    dlc_library = dlopen("libQnnModelDlc.so", RTLD_NOW | RTLD_GLOBAL);
    system_library = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_GLOBAL);
    backend_library = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_GLOBAL);
    if (!dlc_library || !system_library || !backend_library) {
      const char *detail = dlerror();
      return fail(std::string("Unable to load QNN runtime libraries: ") +
                  (detail ? detail : "unknown error"));
    }
    auto get_providers = reinterpret_cast<GetProviders>(
        dlsym(backend_library, "QnnInterface_getProviders"));
    auto get_system_providers = reinterpret_cast<GetSystemProviders>(
        dlsym(system_library, "QnnSystemInterface_getProviders"));
    if (!get_providers || !get_system_providers) {
      return fail("QNN provider entry points are unavailable");
    }
    const QnnInterface_t **providers = nullptr;
    uint32_t provider_count = 0;
    if (get_providers(&providers, &provider_count) != QNN_SUCCESS) {
      return fail("Unable to enumerate QNN HTP providers");
    }
    for (uint32_t i = 0; i < provider_count; ++i) {
      const auto &version = providers[i]->apiVersion.coreApiVersion;
      if (version.major == QNN_API_VERSION_MAJOR &&
          version.minor >= QNN_API_VERSION_MINOR) {
        provider = providers[i];
        break;
      }
    }
    const QnnSystemInterface_t **system_providers = nullptr;
    uint32_t system_provider_count = 0;
    if (get_system_providers(&system_providers, &system_provider_count) !=
        QNN_SUCCESS) {
      return fail("Unable to enumerate QNN system providers");
    }
    for (uint32_t i = 0; i < system_provider_count; ++i) {
      const auto &version = system_providers[i]->systemApiVersion;
      if (version.major == QNN_SYSTEM_API_VERSION_MAJOR &&
          version.minor >= QNN_SYSTEM_API_VERSION_MINOR) {
        system_provider = system_providers[i];
        break;
      }
    }
    if (!provider || !system_provider) {
      return fail("No compatible QNN 2.49 provider was found");
    }
    const auto &api = qnn();
    const auto &sys = system();
    if (!api.backendCreate || !api.contextCreate ||
        !api.contextCreateFromBinary || !api.contextGetBinarySize ||
        !api.contextGetBinary || !api.graphRetrieve || !api.graphFinalize ||
        !api.graphExecute || !sys.systemDlcCreateFromFile ||
        !sys.systemDlcComposeGraphs || !sys.systemDlcFree) {
      return fail("The QNN provider is missing DLC or graph APIs");
    }
    Qnn_ErrorHandle_t status = api.backendCreate(nullptr, nullptr, &backend);
    if (status != QNN_SUCCESS) {
      return fail("QNN backendCreate failed: " + std::to_string(status));
    }
    if (api.deviceCreate) {
      status = api.deviceCreate(nullptr, nullptr, &device);
      if (status == QNN_DEVICE_ERROR_UNSUPPORTED_FEATURE ||
          status == QNN_DEVICE_ERROR_INVALID_CONFIG) {
        device = nullptr;
      } else if (status != QNN_SUCCESS) {
        return fail("QNN deviceCreate failed: " + std::to_string(status));
      }
    }
    error.clear();
    return true;
  }

  void unload_graph() {
    if (context && provider && qnn().contextFree) qnn().contextFree(context, nullptr);
    context = nullptr;
    graph = nullptr;
    graph_name.clear();
    binary.clear();
    inputs.clear();
    outputs.clear();
    input_metadata.clear();
    output_metadata.clear();
  }

  void reset() {
    unload_graph();
    if (provider) {
      const auto &api = qnn();
      if (device && api.deviceFree) api.deviceFree(device);
      if (backend && api.backendFree) api.backendFree(backend);
    }
    device = nullptr;
    backend = nullptr;
    provider = nullptr;
    system_provider = nullptr;
    if (backend_library) dlclose(backend_library);
    if (system_library) dlclose(system_library);
    if (dlc_library) dlclose(dlc_library);
    backend_library = nullptr;
    system_library = nullptr;
    dlc_library = nullptr;
  }

  bool compile_dlc(const std::string &dlc_path,
                   const std::string &binary_path) {
    if (!initialize()) return false;
    std::ifstream existing(binary_path, std::ios::binary | std::ios::ate);
    if (existing && existing.tellg() > 0) return true;

    Qnn_ContextHandle_t compile_context = nullptr;
    QnnSystemDlc_Handle_t dlc = nullptr;
    QnnSystemContext_GraphInfo_t *graph_infos = nullptr;
    uint32_t graph_count = 0;
    auto cleanup = [&]() {
      free_graph_infos(graph_infos, graph_count);
      if (dlc) system().systemDlcFree(dlc);
      if (compile_context) qnn().contextFree(compile_context, nullptr);
    };
    Qnn_ErrorHandle_t status =
        qnn().contextCreate(backend, device, nullptr, &compile_context);
    if (status != QNN_SUCCESS) {
      return fail("QNN contextCreate failed: " + std::to_string(status));
    }
    status = system().systemDlcCreateFromFile(nullptr, dlc_path.c_str(), &dlc);
    if (status != QNN_SUCCESS) {
      cleanup();
      return fail("Unable to open DLC " + dlc_path + ": " +
                  std::to_string(status));
    }
    status = system().systemDlcComposeGraphs(
        dlc, nullptr, 0, backend, compile_context, *provider,
        QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3, &graph_infos, &graph_count);
    if (status != QNN_SUCCESS || !graph_infos || graph_count == 0) {
      cleanup();
      return fail("QNN DLC graph composition failed: " +
                  std::to_string(status));
    }
    for (uint32_t i = 0; i < graph_count; ++i) {
      const char *name = nullptr;
      const Qnn_Tensor_t *graph_inputs = nullptr;
      const Qnn_Tensor_t *graph_outputs = nullptr;
      uint32_t input_count = 0, output_count = 0;
      if (!graph_fields(graph_infos[i], name, graph_inputs, input_count,
                        graph_outputs, output_count) || !name) {
        cleanup();
        return fail("DLC returned invalid graph metadata");
      }
      Qnn_GraphHandle_t graph_handle = nullptr;
      status = qnn().graphRetrieve(compile_context, name, &graph_handle);
      if (status == QNN_SUCCESS) {
        status = qnn().graphFinalize(graph_handle, nullptr, nullptr);
      }
      if (status != QNN_SUCCESS) {
        cleanup();
        return fail("QNN graph compilation failed for " + std::string(name) +
                    ": " + std::to_string(status));
      }
    }
    Qnn_ContextBinarySize_t binary_size = 0;
    status = qnn().contextGetBinarySize(compile_context, &binary_size);
    if (status != QNN_SUCCESS || binary_size == 0) {
      cleanup();
      return fail("Unable to determine compiled QNN binary size");
    }
    std::vector<uint8_t> compiled(static_cast<size_t>(binary_size));
    Qnn_ContextBinarySize_t written = 0;
    status = qnn().contextGetBinary(compile_context, compiled.data(), binary_size,
                                    &written);
    if (status != QNN_SUCCESS || written == 0 || written > binary_size) {
      cleanup();
      return fail("Unable to extract compiled QNN context: " +
                  std::to_string(status));
    }
    const std::string partial = binary_path + ".partial";
    std::ofstream output(partial, std::ios::binary | std::ios::trunc);
    output.write(reinterpret_cast<const char *>(compiled.data()),
                 static_cast<std::streamsize>(written));
    output.close();
    if (!output || std::rename(partial.c_str(), binary_path.c_str()) != 0) {
      std::remove(partial.c_str());
      cleanup();
      return fail("Unable to persist compiled QNN context: " +
                  std::string(std::strerror(errno)));
    }
    cleanup();
    error.clear();
    return true;
  }

  bool load(const std::string &path) {
    if (!initialize()) return false;
    unload_graph();
    std::ifstream stream(path, std::ios::binary | std::ios::ate);
    if (!stream || stream.tellg() <= 0) return fail("Unable to open " + path);
    binary.resize(static_cast<size_t>(stream.tellg()));
    stream.seekg(0);
    if (!stream.read(reinterpret_cast<char *>(binary.data()),
                     static_cast<std::streamsize>(binary.size()))) {
      return fail("Unable to read " + path);
    }

    QnnSystemContext_Handle_t system_context = nullptr;
    const QnnSystemContext_BinaryInfo_t *info = nullptr;
    Qnn_ContextBinarySize_t info_size = 0;
    Qnn_ErrorHandle_t status = system().systemContextCreate(&system_context);
    if (status != QNN_SUCCESS) return fail("systemContextCreate failed");
    status = system().systemContextGetBinaryInfo(
        system_context, binary.data(), binary.size(), &info, &info_size);
    const QnnSystemContext_GraphInfo_t *graphs = nullptr;
    uint32_t graph_count = 0;
    if (status != QNN_SUCCESS || !binary_graphs(info, graphs, graph_count) ||
        graph_count != 1) {
      system().systemContextFree(system_context);
      return fail("Expected one graph in compiled depth context");
    }
    const char *name = nullptr;
    const Qnn_Tensor_t *graph_inputs = nullptr, *graph_outputs = nullptr;
    uint32_t input_count = 0, output_count = 0;
    if (!graph_fields(graphs[0], name, graph_inputs, input_count, graph_outputs,
                      output_count) || !name || !graph_inputs || !graph_outputs) {
      system().systemContextFree(system_context);
      return fail("Invalid graph metadata in compiled depth context");
    }
    graph_name = name;
    inputs.resize(input_count);
    outputs.resize(output_count);
    for (uint32_t i = 0; i < input_count; ++i) {
      if (!copy_tensor(graph_inputs[i], inputs[i])) {
        system().systemContextFree(system_context);
        return fail("Unsupported depth input tensor metadata");
      }
    }
    for (uint32_t i = 0; i < output_count; ++i) {
      if (!copy_tensor(graph_outputs[i], outputs[i])) {
        system().systemContextFree(system_context);
        return fail("Unsupported depth output tensor metadata");
      }
    }
    system().systemContextFree(system_context);
    status = qnn().contextCreateFromBinary(backend, device, nullptr, binary.data(),
                                           binary.size(), &context, nullptr);
    if (status == QNN_SUCCESS) status = qnn().graphRetrieve(context, graph_name.c_str(), &graph);
    if (status != QNN_SUCCESS) {
      unload_graph();
      return fail("Unable to load compiled depth graph: " +
                  std::to_string(status));
    }
    input_metadata.clear();
    output_metadata.clear();
    for (const auto &tensor : inputs)
      input_metadata.push_back({tensor.name, tensor.dimensions, {}});
    for (const auto &tensor : outputs)
      output_metadata.push_back({tensor.name, tensor.dimensions, {}});
    error.clear();
    return true;
  }

  bool encode(const TensorData &source, OwnedTensor &target) {
    size_t count = 0;
    if (!checked_elements(target.dimensions, count) || source.values.size() != count) {
      return fail("Tensor element count mismatch for " + target.name);
    }
    const size_t bytes_per_element = element_size(target.tensor.v2.dataType);
    if (!target.buffer.resize(count * bytes_per_element)) {
      return fail("Unable to allocate QNN input buffer for " + target.name);
    }
    if (target.tensor.v2.dataType == QNN_DATATYPE_FLOAT_32) {
      std::memcpy(target.buffer.data, source.values.data(), count * sizeof(float));
    } else if (target.tensor.v2.dataType == QNN_DATATYPE_FLOAT_16) {
      auto *destination = reinterpret_cast<uint16_t *>(target.buffer.data);
      for (size_t i = 0; i < count; ++i) destination[i] = float_to_half(source.values[i]);
    } else {
      float scale = 0.0f;
      int32_t offset = 0;
      if (!scale_offset(target.tensor, scale, offset))
        return fail("Unsupported quantization for " + target.name);
      if (target.tensor.v2.dataType == QNN_DATATYPE_UFIXED_POINT_16) {
        auto *destination = reinterpret_cast<uint16_t *>(target.buffer.data);
        for (size_t i = 0; i < count; ++i) {
          const long value = std::lround(source.values[i] / scale) - offset;
          destination[i] = static_cast<uint16_t>(std::clamp(value, 0L, 65535L));
        }
      } else if (target.tensor.v2.dataType == QNN_DATATYPE_UFIXED_POINT_8) {
        for (size_t i = 0; i < count; ++i) {
          const long value = std::lround(source.values[i] / scale) - offset;
          target.buffer.data[i] = static_cast<uint8_t>(std::clamp(value, 0L, 255L));
        }
      } else {
        return fail("Unsupported QNN input data type for " + target.name);
      }
    }
    target.tensor.v2.clientBuf.data = target.buffer.data;
    target.tensor.v2.clientBuf.dataSize =
        static_cast<uint32_t>(count * bytes_per_element);
    return true;
  }

  bool decode(const OwnedTensor &source, TensorData &target) {
    size_t count = 0;
    if (!checked_elements(source.dimensions, count)) return false;
    target = {source.name, source.dimensions, std::vector<float>(count)};
    if (source.tensor.v2.dataType == QNN_DATATYPE_FLOAT_32) {
      std::memcpy(target.values.data(), source.buffer.data, count * sizeof(float));
    } else if (source.tensor.v2.dataType == QNN_DATATYPE_FLOAT_16) {
      const auto *values = reinterpret_cast<const uint16_t *>(source.buffer.data);
      for (size_t i = 0; i < count; ++i) target.values[i] = half_to_float(values[i]);
    } else {
      float scale = 0.0f;
      int32_t offset = 0;
      if (!scale_offset(source.tensor, scale, offset))
        return fail("Unsupported output quantization for " + source.name);
      if (source.tensor.v2.dataType == QNN_DATATYPE_UFIXED_POINT_16) {
        const auto *values = reinterpret_cast<const uint16_t *>(source.buffer.data);
        for (size_t i = 0; i < count; ++i)
          target.values[i] = (static_cast<int32_t>(values[i]) + offset) * scale;
      } else if (source.tensor.v2.dataType == QNN_DATATYPE_UFIXED_POINT_8) {
        for (size_t i = 0; i < count; ++i)
          target.values[i] = (static_cast<int32_t>(source.buffer.data[i]) + offset) * scale;
      } else {
        return fail("Unsupported QNN output data type for " + source.name);
      }
    }
    return true;
  }

  bool execute(const std::vector<TensorData> &provided_inputs,
               std::vector<TensorData> &provided_outputs) {
    if (!graph || provided_inputs.size() != inputs.size())
      return fail("Depth graph is not loaded or input count is incorrect");
    std::vector<Qnn_Tensor_t> input_tensors(inputs.size());
    std::vector<Qnn_Tensor_t> output_tensors(outputs.size());
    for (size_t i = 0; i < inputs.size(); ++i) {
      auto found = std::find_if(provided_inputs.begin(), provided_inputs.end(),
                                [&](const TensorData &value) {
                                  return value.name == inputs[i].name;
                                });
      if (found == provided_inputs.end() || !encode(*found, inputs[i])) return false;
      input_tensors[i] = inputs[i].tensor;
    }
    for (size_t i = 0; i < outputs.size(); ++i) {
      size_t count = 0;
      if (!checked_elements(outputs[i].dimensions, count))
        return fail("Invalid output dimensions for " + outputs[i].name);
      const size_t bytes = count * element_size(outputs[i].tensor.v2.dataType);
      if (!outputs[i].buffer.resize(bytes))
        return fail("Unable to allocate output buffer for " + outputs[i].name);
      outputs[i].tensor.v2.clientBuf.data = outputs[i].buffer.data;
      outputs[i].tensor.v2.clientBuf.dataSize = static_cast<uint32_t>(bytes);
      output_tensors[i] = outputs[i].tensor;
    }
    const Qnn_ErrorHandle_t status = qnn().graphExecute(
        graph, input_tensors.data(), static_cast<uint32_t>(input_tensors.size()),
        output_tensors.data(), static_cast<uint32_t>(output_tensors.size()),
        nullptr, nullptr);
    if (status != QNN_SUCCESS)
      return fail("QNN graphExecute failed: " + std::to_string(status));
    provided_outputs.resize(outputs.size());
    for (size_t i = 0; i < outputs.size(); ++i)
      if (!decode(outputs[i], provided_outputs[i])) return false;
    error.clear();
    return true;
  }

  bool execute_to_files(const std::vector<TensorData> &provided_inputs,
                        const std::string &output_directory) {
    if (!graph || provided_inputs.size() != inputs.size())
      return fail("Depth graph is not loaded or input count is incorrect");
    if (mkdir(output_directory.c_str(), 0700) != 0 && errno != EEXIST)
      return fail("Unable to create output directory " + output_directory);

    std::vector<Qnn_Tensor_t> input_tensors(inputs.size());
    std::vector<Qnn_Tensor_t> output_tensors(outputs.size());
    for (size_t i = 0; i < inputs.size(); ++i) {
      auto found = std::find_if(provided_inputs.begin(), provided_inputs.end(),
                                [&](const TensorData &value) {
                                  return value.name == inputs[i].name;
                                });
      if (found == provided_inputs.end() || !encode(*found, inputs[i])) return false;
      input_tensors[i] = inputs[i].tensor;
    }
    for (size_t i = 0; i < outputs.size(); ++i) {
      size_t count = 0;
      if (!checked_elements(outputs[i].dimensions, count))
        return fail("Invalid output dimensions for " + outputs[i].name);
      const size_t bytes = count * element_size(outputs[i].tensor.v2.dataType);
      if (!outputs[i].buffer.resize(bytes))
        return fail("Unable to allocate output buffer for " + outputs[i].name);
      outputs[i].tensor.v2.clientBuf.data = outputs[i].buffer.data;
      outputs[i].tensor.v2.clientBuf.dataSize = static_cast<uint32_t>(bytes);
      output_tensors[i] = outputs[i].tensor;
    }
    const Qnn_ErrorHandle_t status = qnn().graphExecute(
        graph, input_tensors.data(), static_cast<uint32_t>(input_tensors.size()),
        output_tensors.data(), static_cast<uint32_t>(output_tensors.size()),
        nullptr, nullptr);
    if (status != QNN_SUCCESS)
      return fail("QNN graphExecute failed: " + std::to_string(status));

    for (size_t i = 0; i < outputs.size(); ++i) {
      TensorData decoded;
      if (!decode(outputs[i], decoded)) return false;
      const std::string path = output_directory + "/" +
          Runtime::safe_tensor_filename(outputs[i].name) + ".raw";
      std::ofstream stream(path, std::ios::binary | std::ios::trunc);
      stream.write(reinterpret_cast<const char *>(decoded.values.data()),
                   static_cast<std::streamsize>(decoded.values.size() * sizeof(float)));
      stream.close();
      decoded.values.clear();
      outputs[i].buffer.clear();
      if (!stream) return fail("Unable to write QNN output " + path);
    }
    error.clear();
    return true;
  }
#else
  bool initialize() { error = "QNN is available only in arm64 builds"; return false; }
  void unload_graph() {}
  void reset() {}
  bool compile_dlc(const std::string &, const std::string &) { return initialize(); }
  bool load(const std::string &) { return initialize(); }
  bool execute(const std::vector<TensorData> &, std::vector<TensorData> &) { return initialize(); }
  bool execute_to_files(const std::vector<TensorData> &, const std::string &) { return initialize(); }
#endif
};

Runtime::Runtime() : impl_(new Impl()) {}
Runtime::~Runtime() { delete impl_; }
bool Runtime::initialize() { return impl_->initialize(); }
bool Runtime::compile_dlc(const std::string &dlc, const std::string &binary) {
  return impl_->compile_dlc(dlc, binary);
}
bool Runtime::load(const std::string &path) { return impl_->load(path); }
bool Runtime::execute(const std::vector<TensorData> &inputs,
                      std::vector<TensorData> &outputs) {
  return impl_->execute(inputs, outputs);
}
bool Runtime::execute_to_files(const std::vector<TensorData> &inputs,
                               const std::string &output_directory) {
  return impl_->execute_to_files(inputs, output_directory);
}
void Runtime::unload_graph() { impl_->unload_graph(); }
void Runtime::reset() { impl_->reset(); }
const std::vector<TensorData> &Runtime::input_metadata() const {
  return impl_->input_metadata;
}
const std::vector<TensorData> &Runtime::output_metadata() const {
  return impl_->output_metadata;
}
const std::string &Runtime::error() const { return impl_->error; }

std::string Runtime::safe_tensor_filename(const std::string &name) {
  std::string result = name;
  for (char &value : result) {
    if (!std::isalnum(static_cast<unsigned char>(value)) && value != '-' &&
        value != '_' && value != '.') value = '_';
  }
  return result;
}

} // namespace spatial_qnn
