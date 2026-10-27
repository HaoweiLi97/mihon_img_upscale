#include "qnn_ondevice_quantizer.h"
#include "spatial_qnn_runtime.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <dlfcn.h>
#include <fstream>
#include <limits>
#include <map>
#include <mutex>
#include <utility>
#include <vector>

#if MIHON_ENABLE_QNN
#include <CPU/QnnCpuGraph.h>
#include <HTP/QnnHtpDevice.h>
#include <QnnInterface.h>
#include <System/QnnSystemInterface.h>

namespace qnn_ondevice {
namespace {

float from_half(uint16_t value) {
  const unsigned exponent = (value >> 10) & 31;
  if (exponent == 31)
    return std::numeric_limits<float>::quiet_NaN();
  const float result = exponent
                           ? std::ldexp(1.0f + (value & 1023) / 1024.0f,
                                        static_cast<int>(exponent) - 15)
                           : std::ldexp(static_cast<float>(value & 1023), -24);
  return (value & 32768) ? -result : result;
}

bool floating(Qnn_DataType_t type) {
  return type == QNN_DATATYPE_FLOAT_16 || type == QNN_DATATYPE_FLOAT_32;
}

struct Tensor {
  std::string name;
  std::vector<uint32_t> dimensions;
  Qnn_Tensor_t original = QNN_TENSOR_INIT;
  std::vector<float> constants;
  std::vector<uint8_t> quantized;
  std::vector<Qnn_ScaleOffset_t> channels;
  Qnn_QuantizeParams_t encoding = QNN_QUANTIZE_PARAMS_INIT;
  Qnn_DataType_t quantized_type = QNN_DATATYPE_UNDEFINED;
  float minimum = 0.0f;
  float maximum = 0.0f;
  size_t observations = 0;
  size_t count = 0;
  int weight_axis = -1;
  std::string bias_input;
  std::string bias_weight;

  bool observe(const float *data, size_t size) {
    for (size_t i = 0; i < size; ++i) {
      if (!std::isfinite(data[i]))
        return false;
      minimum = std::min(minimum, data[i]);
      maximum = std::max(maximum, data[i]);
    }
    observations += size;
    return true;
  }
};

class Quantizer;
thread_local Quantizer *composing = nullptr;

class Quantizer {
public:
  ~Quantizer() {
    close_backend();
    if (dlc && sys)
      sys->systemDlcFree(dlc);
    if (system_library)
      dlclose(system_library);
    if (dlc_library)
      dlclose(dlc_library);
  }

  std::string error;
  std::map<std::string, Tensor> tensors;
  std::map<uint32_t, Tensor *> tensor_ids;
  std::mutex observation_mutex;
  std::string input_name, output_name;
  std::vector<uint32_t> input_shape, output_shape;
  size_t input_count = 0, output_count = 0;
  const QnnInterface_t *provider = nullptr;
  const QNN_INTERFACE_VER_TYPE *api = nullptr;
  const QNN_SYSTEM_INTERFACE_VER_TYPE *sys = nullptr;
  Qnn_BackendHandle_t backend = nullptr;
  Qnn_DeviceHandle_t device = nullptr;
  Qnn_ContextHandle_t context = nullptr;
  Qnn_GraphHandle_t graph = nullptr;
  QnnSystemDlc_Handle_t dlc = nullptr;
  Qnn_Tensor_t input = QNN_TENSOR_INIT, output = QNN_TENSOR_INIT;
  bool integer_graph = false;
  bool collecting = true;

  bool fail(const std::string &message) {
    error = message;
    return false;
  }
  bool ok(Qnn_ErrorHandle_t status, const char *action) {
    return status == QNN_SUCCESS ||
           fail(std::string(action) + ": " + std::to_string(status));
  }

  bool open(const std::string &path) {
    dlc_library = dlopen("libQnnModelDlc.so", RTLD_NOW | RTLD_GLOBAL);
    system_library = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_GLOBAL);
    if (!dlc_library || !system_library)
      return fail("DLC runtime is unavailable");
    using Get =
        Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t ***, uint32_t *);
    auto get = reinterpret_cast<Get>(
        dlsym(system_library, "QnnSystemInterface_getProviders"));
    const QnnSystemInterface_t **providers = nullptr;
    uint32_t count = 0;
    if (!get || !ok(get(&providers, &count), "System providers"))
      return false;
    for (uint32_t i = 0; i < count; ++i) {
      const auto version = providers[i]->systemApiVersion;
      if (version.major == QNN_SYSTEM_API_VERSION_MAJOR &&
          version.minor >= QNN_SYSTEM_API_VERSION_MINOR) {
        sys = &providers[i]->QNN_SYSTEM_INTERFACE_VER_NAME;
        break;
      }
    }
    return sys && ok(sys->systemDlcCreateFromFile(nullptr, path.c_str(), &dlc),
                     "Open calibration model");
  }

  void close_backend() {
    if (api) {
      if (context)
        api->contextFree(context, nullptr);
      if (device && api->deviceFree)
        api->deviceFree(device);
      if (backend)
        api->backendFree(backend);
    }
    context = nullptr;
    backend = nullptr;
    device = nullptr;
    graph = nullptr;
    api = nullptr;
    provider = nullptr;
    if (backend_library)
      dlclose(backend_library);
    backend_library = nullptr;
    tensor_ids.clear();
  }

  Tensor *record(const Qnn_Tensor_t &source) {
    if (source.version != QNN_TENSOR_VERSION_2 || !source.v2.name ||
        !source.v2.dimensions || source.v2.rank == 0 || source.v2.rank > 8) {
      fail("Unsupported tensor metadata in calibration model");
      return nullptr;
    }
    auto found = tensors.find(source.v2.name);
    if (found != tensors.end())
      return &found->second;
    if (integer_graph) {
      fail("Integer graph introduced an uncalibrated tensor");
      return nullptr;
    }
    auto &tensor = tensors[source.v2.name];
    tensor.name = source.v2.name;
    tensor.original = source;
    tensor.dimensions.assign(source.v2.dimensions,
                             source.v2.dimensions + source.v2.rank);
    tensor.count = 1;
    for (auto dimension : tensor.dimensions) {
      if (dimension == 0 || tensor.count > (128u * 1024u * 1024u) / dimension) {
        fail("Calibration tensor exceeds the memory limit");
        return nullptr;
      }
      tensor.count *= dimension;
    }
    if (source.v2.type == QNN_TENSOR_TYPE_STATIC &&
        floating(source.v2.dataType)) {
      const size_t bytes = source.v2.dataType == QNN_DATATYPE_FLOAT_16 ? 2 : 4;
      if (!source.v2.clientBuf.data ||
          source.v2.clientBuf.dataSize != tensor.count * bytes) {
        fail("Invalid floating-point model weights");
        return nullptr;
      }
      tensor.constants.resize(tensor.count);
      for (size_t i = 0; i < tensor.count; ++i) {
        if (bytes == 2) {
          uint16_t value;
          std::memcpy(&value,
                      static_cast<const uint8_t *>(source.v2.clientBuf.data) +
                          i * 2,
                      2);
          tensor.constants[i] = from_half(value);
        } else {
          std::memcpy(&tensor.constants[i],
                      static_cast<const uint8_t *>(source.v2.clientBuf.data) +
                          i * 4,
                      4);
        }
      }
      if (!tensor.observe(tensor.constants.data(), tensor.count)) {
        fail("Model weights contain nonfinite values");
        return nullptr;
      }
    }
    return &tensor;
  }

  bool transform(Qnn_Tensor_t &value) {
    auto *tensor = record(value);
    if (!tensor)
      return false;
    if (!floating(value.v2.dataType))
      return true;
    value.v2.quantizeParams =
        integer_graph ? tensor->encoding
                      : Qnn_QuantizeParams_t QNN_QUANTIZE_PARAMS_INIT;
    value.v2.dataType =
        integer_graph ? tensor->quantized_type : QNN_DATATYPE_FLOAT_32;
    if (value.v2.type == QNN_TENSOR_TYPE_STATIC) {
      if (integer_graph) {
        value.v2.clientBuf = {tensor->quantized.data(),
                              static_cast<uint32_t>(tensor->quantized.size())};
      } else {
        value.v2.clientBuf = {
            tensor->constants.data(),
            static_cast<uint32_t>(tensor->constants.size() * sizeof(float))};
      }
    }
    return true;
  }

  static Qnn_ErrorHandle_t create_tensor(Qnn_GraphHandle_t graph,
                                         Qnn_Tensor_t *value) {
    auto &self = *composing;
    const auto original = *value;
    if (!self.transform(*value))
      return QNN_COMMON_ERROR_INVALID_ARGUMENT;
    const auto status = self.api->tensorCreateGraphTensor(graph, value);
    const auto id = value->v2.id;
    if (status == QNN_SUCCESS)
      self.tensor_ids[id] = &self.tensors[value->v2.name];
    // The DLC composer owns the original weight buffer and verifies it was not
    // replaced by the backend. Only propagate the assigned tensor id to it.
    *value = original;
    value->v2.id = id;
    return status;
  }

  static Qnn_ErrorHandle_t observe_op(Qnn_OpConfig_t *op, void *parameter) {
    auto &self = *static_cast<Quantizer *>(parameter);
    if (!self.collecting)
      return QNN_SUCCESS;
    std::lock_guard<std::mutex> lock(self.observation_mutex);
    for (uint32_t i = 0; i < op->v1.numOfOutputs; ++i) {
      const auto &value = op->v1.outputTensors[i];
      if (value.version != QNN_TENSOR_VERSION_2)
        return QNN_COMMON_ERROR_INVALID_ARGUMENT;
      const auto found = self.tensor_ids.find(value.v2.id);
      if (found == self.tensor_ids.end())
        return QNN_COMMON_ERROR_INVALID_ARGUMENT;
      auto &tensor = *found->second;
      if (!floating(tensor.original.v2.dataType))
        continue;
      // CPU debug callbacks guarantee only the tensor id and data buffer.
      if (!value.v2.clientBuf.data ||
          value.v2.clientBuf.dataSize != tensor.count * sizeof(float) ||
          !tensor.observe(static_cast<const float *>(value.v2.clientBuf.data),
                          tensor.count)) {
        self.fail(
            "Calibration produced missing or nonfinite intermediate values");
        return QNN_COMMON_ERROR_INVALID_ARGUMENT;
      }
    }
    return QNN_SUCCESS;
  }

  static Qnn_ErrorHandle_t create_graph(Qnn_ContextHandle_t context,
                                        const char *name,
                                        const QnnGraph_Config_t **configs,
                                        Qnn_GraphHandle_t *graph) {
    auto &self = *composing;
    if (self.integer_graph)
      return self.api->graphCreate(context, name, configs, graph);
    QnnCpuGraph_CustomConfig_t cpu = QNN_CPU_GRAPH_CUSTOM_CONFIG_INIT;
    cpu.option = QNN_CPU_GRAPH_CONFIG_OPTION_OP_DEBUG_CALLBACK;
    cpu.cpuGraphOpDebug = {&self, observe_op};
    QnnGraph_Config_t config = QNN_GRAPH_CONFIG_INIT;
    config.option = QNN_GRAPH_CONFIG_OPTION_CUSTOM;
    config.customConfig = &cpu;
    std::vector<const QnnGraph_Config_t *> all;
    if (configs)
      while (*configs)
        all.push_back(*configs++);
    all.push_back(&config);
    all.push_back(nullptr);
    return self.api->graphCreate(context, name, all.data(), graph);
  }

  static Qnn_ErrorHandle_t operation(Qnn_Handle_t handle, Qnn_OpConfig_t op,
                                     bool validate) {
    auto &self = *composing;
    if (op.version != QNN_OPCONFIG_VERSION_1)
      return QNN_COMMON_ERROR_INVALID_ARGUMENT;
    std::vector<Qnn_Tensor_t> inputs(op.v1.inputTensors,
                                     op.v1.inputTensors + op.v1.numOfInputs);
    std::vector<Qnn_Tensor_t> outputs(op.v1.outputTensors,
                                      op.v1.outputTensors + op.v1.numOfOutputs);
    if (!self.integer_graph && !validate)
      self.record_roles(op);
    for (auto &tensor : inputs)
      if (!self.transform(tensor))
        return QNN_COMMON_ERROR_INVALID_ARGUMENT;
    for (auto &tensor : outputs)
      if (!self.transform(tensor))
        return QNN_COMMON_ERROR_INVALID_ARGUMENT;
    op.v1.inputTensors = inputs.data();
    op.v1.outputTensors = outputs.data();
    return validate ? self.api->backendValidateOpConfig(handle, op)
                    : self.api->graphAddNode(handle, op);
  }
  static Qnn_ErrorHandle_t validate(Qnn_BackendHandle_t handle,
                                    Qnn_OpConfig_t op) {
    return operation(handle, op, true);
  }
  static Qnn_ErrorHandle_t add(Qnn_GraphHandle_t handle, Qnn_OpConfig_t op) {
    return operation(handle, op, false);
  }

  void record_roles(const Qnn_OpConfig_t &op) {
    const std::string type = op.v1.typeName;
    if ((type != "Conv2d" && type != "TransposeConv2d" &&
         type != "DepthWiseConv2d" && type != "FullyConnected") ||
        op.v1.numOfInputs < 2)
      return;
    auto *weight = record(op.v1.inputTensors[1]);
    if (!weight || weight->constants.empty()) {
      fail("Convolution has nonconstant weights");
      return;
    }
    weight->weight_axis = type == "FullyConnected"
                              ? 0
                              : static_cast<int>(weight->dimensions.size()) - 1;
    if (op.v1.numOfInputs > 2) {
      auto *bias = record(op.v1.inputTensors[2]);
      if (bias) {
        bias->bias_input = op.v1.inputTensors[0].v2.name;
        bias->bias_weight = weight->name;
      }
    }
  }

  bool compose(bool quantized) {
    close_backend();
    integer_graph = quantized;
    backend_library = dlopen(quantized ? "libQnnHtp.so" : "libQnnCpu.so",
                             RTLD_NOW | RTLD_GLOBAL);
    if (!backend_library)
      return fail(quantized ? "HTP runtime is unavailable"
                            : "CPU calibration runtime is unavailable");
    using Get = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);
    auto get = reinterpret_cast<Get>(
        dlsym(backend_library, "QnnInterface_getProviders"));
    const QnnInterface_t **providers = nullptr;
    uint32_t count = 0;
    if (!get || !ok(get(&providers, &count), "Backend providers"))
      return false;
    for (uint32_t i = 0; i < count; ++i) {
      const auto version = providers[i]->apiVersion.coreApiVersion;
      if (version.major == QNN_API_VERSION_MAJOR &&
          version.minor >= QNN_API_VERSION_MINOR) {
        provider = providers[i];
        break;
      }
    }
    if (!provider)
      return fail("Incompatible QNN calibration runtime");
    api = &provider->QNN_INTERFACE_VER_NAME;
    if (!ok(api->backendCreate(nullptr, nullptr, &backend),
            "Create calibration backend"))
      return false;
    if (quantized && api->deviceCreate) {
      const QnnDevice_Config_t **configs = nullptr;
#ifdef MIHON_QNN_HOST_TEST
      QnnHtpDevice_CustomConfig_t target = {};
      target.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
      const char *test_soc = std::getenv("MIHON_QNN_TEST_SOC");
      target.socModel =
          test_soc ? static_cast<uint32_t>(std::atoi(test_soc)) : 43;
      QnnDevice_Config_t config = QNN_DEVICE_CONFIG_INIT;
      config.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
      config.customConfig = &target;
      const QnnDevice_Config_t *test_configs[] = {&config, nullptr};
      configs = test_configs;
#endif
      const auto device_status = api->deviceCreate(nullptr, configs, &device);
#ifndef MIHON_QNN_HOST_TEST
      if (device_status == QNN_DEVICE_ERROR_UNSUPPORTED_FEATURE ||
          device_status == QNN_DEVICE_ERROR_INVALID_CONFIG) {
        device = nullptr;
      } else
#endif
          if (!ok(device_status, "Create target HTP device"))
        return false;
    }
    if (!ok(api->contextCreate(backend, device, nullptr, &context),
            "Create calibration context"))
      return false;
    QnnInterface_t wrapper = *provider;
    auto &hooks = wrapper.QNN_INTERFACE_VER_NAME;
    hooks.graphCreate = create_graph;
    hooks.tensorCreateGraphTensor = create_tensor;
    hooks.graphAddNode = add;
    hooks.backendValidateOpConfig = validate;
    QnnSystemContext_GraphInfo_t *infos = nullptr;
    composing = this;
    const auto status = sys->systemDlcComposeGraphs(
        dlc, nullptr, 0, backend, context, wrapper,
        QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3, &infos, &count);
    composing = nullptr;
    bool success = ok(status, quantized ? "Compose W8A16 graph"
                                        : "Compose CPU calibration graph");
    if (success &&
        (count != 1 || !infos ||
         infos[0].version != QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3 ||
         infos[0].graphInfoV3.numGraphInputs != 1 ||
         infos[0].graphInfoV3.numGraphOutputs != 1)) {
      success = fail("Calibration requires one RGB input and output");
    }
    if (success) {
      auto &info = infos[0].graphInfoV3;
      input_name = info.graphInputs[0].v2.name;
      output_name = info.graphOutputs[0].v2.name;
      input = info.graphInputs[0];
      output = info.graphOutputs[0];
      auto &in = tensors[input_name];
      auto &out = tensors[output_name];
      input_shape = in.dimensions;
      output_shape = out.dimensions;
      input_count = in.count;
      output_count = out.count;
      input.v2.name = input_name.c_str();
      output.v2.name = output_name.c_str();
      input.v2.dimensions = input_shape.data();
      output.v2.dimensions = output_shape.data();
      success = transform(input) && transform(output) &&
                ok(api->graphRetrieve(context, info.graphName, &graph),
                   "Retrieve calibration graph");
    }
    if (infos) {
      for (uint32_t i = 0; i < count; ++i) {
        std::free(const_cast<char *>(infos[i].graphInfoV3.graphName));
        std::free(infos[i].graphInfoV3.graphInputs);
        std::free(infos[i].graphInfoV3.graphOutputs);
      }
      std::free(infos);
    }
    return success && error.empty() &&
           ok(api->graphFinalize(graph, nullptr, nullptr),
              "Finalize calibrated graph");
  }

  bool read_image(const std::string &path, std::vector<float> &values) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file || file.tellg() != static_cast<std::streamoff>(input_count))
      return fail("Invalid RGB calibration sample");
    file.seekg(0);
    std::vector<uint8_t> bytes(input_count);
    if (!file.read(reinterpret_cast<char *>(bytes.data()), bytes.size()))
      return fail("Unable to read calibration sample");
    values.resize(input_count);
    for (size_t i = 0; i < input_count; ++i)
      values[i] = bytes[i] / 255.0f;
    return true;
  }

  bool execute_cpu(const std::vector<float> &values,
                   std::vector<float> &result) {
    result.resize(output_count);
    input.v2.clientBuf = {const_cast<float *>(values.data()),
                          static_cast<uint32_t>(values.size() * sizeof(float))};
    output.v2.clientBuf = {
        result.data(), static_cast<uint32_t>(result.size() * sizeof(float))};
    return (!collecting ||
            tensors[input_name].observe(values.data(), values.size())) &&
           ok(api->graphExecute(graph, &input, 1, &output, 1, nullptr, nullptr),
              "Execute CPU calibration");
  }

  bool encode() {
    for (auto &[name, tensor] : tensors) {
      if (!floating(tensor.original.v2.dataType))
        continue;
      if (!tensor.observations)
        return fail("No calibration observations for " + name);
      auto &encoding = tensor.encoding;
      encoding.encodingDefinition = QNN_DEFINITION_DEFINED;
      encoding.quantizationEncoding = QNN_QUANTIZATION_ENCODING_SCALE_OFFSET;
      encoding.scaleOffsetEncoding.scale =
          std::max((tensor.maximum - tensor.minimum) / 65535.0f, 1.0e-12f);
      encoding.scaleOffsetEncoding.offset = static_cast<int32_t>(
          std::round(tensor.minimum / encoding.scaleOffsetEncoding.scale));
      tensor.quantized_type = QNN_DATATYPE_UFIXED_POINT_16;
      if (tensor.weight_axis < 0)
        continue;
      const auto channels = tensor.dimensions[tensor.weight_axis];
      size_t inner = 1;
      for (size_t i = tensor.weight_axis + 1; i < tensor.dimensions.size(); ++i)
        inner *= tensor.dimensions[i];
      tensor.channels.assign(channels, {1.0e-12f, 0});
      for (size_t i = 0; i < tensor.constants.size(); ++i) {
        auto &channel = tensor.channels[(i / inner) % channels];
        channel.scale =
            std::max(channel.scale, std::abs(tensor.constants[i]) / 127.0f);
      }
      encoding.quantizationEncoding =
          QNN_QUANTIZATION_ENCODING_AXIS_SCALE_OFFSET;
      encoding.axisScaleOffsetEncoding = {tensor.weight_axis, channels,
                                          tensor.channels.data()};
      tensor.quantized_type = QNN_DATATYPE_SFIXED_POINT_8;
    }
    // Enlarge each weight channel's scale when necessary to represent its bias
    // in INT32. This is essential for channels with near-zero calibration
    // range.
    for (auto &[name, bias] : tensors) {
      if (bias.bias_weight.empty())
        continue;
      auto &weight = tensors[bias.bias_weight];
      const auto &source = tensors[bias.bias_input];
      if (weight.channels.size() != bias.count)
        return fail("Unsupported convolution bias shape");
      for (size_t i = 0; i < bias.count; ++i) {
        const double minimum_scale =
            std::abs(static_cast<double>(bias.constants[i])) /
            (2147483647.0 * source.encoding.scaleOffsetEncoding.scale);
        weight.channels[i].scale =
            std::max(weight.channels[i].scale,
                     static_cast<float>(minimum_scale * 1.000001));
      }
    }
    for (auto &[name, tensor] : tensors) {
      if (!floating(tensor.original.v2.dataType) || tensor.constants.empty())
        continue;
      size_t bytes =
          tensor.quantized_type == QNN_DATATYPE_SFIXED_POINT_8 ? 1 : 2;
      if (!tensor.bias_weight.empty()) {
        auto &weight = tensors[tensor.bias_weight];
        auto &source = tensors[tensor.bias_input];
        if (weight.channels.size() != tensor.count)
          return fail("Unsupported convolution bias shape");
        tensor.channels = weight.channels;
        for (auto &channel : tensor.channels)
          channel.scale *= source.encoding.scaleOffsetEncoding.scale;
        tensor.encoding.quantizationEncoding =
            QNN_QUANTIZATION_ENCODING_AXIS_SCALE_OFFSET;
        tensor.encoding.axisScaleOffsetEncoding = {
            0, static_cast<uint32_t>(tensor.count), tensor.channels.data()};
        tensor.quantized_type = QNN_DATATYPE_SFIXED_POINT_32;
        bytes = 4;
      }
      tensor.quantized.resize(tensor.count * bytes);
      size_t inner = 1;
      if (tensor.weight_axis >= 0)
        for (size_t i = tensor.weight_axis + 1; i < tensor.dimensions.size();
             ++i)
          inner *= tensor.dimensions[i];
      for (size_t i = 0; i < tensor.count; ++i) {
        const auto encoding =
            tensor.channels.empty()
                ? tensor.encoding.scaleOffsetEncoding
                : tensor.channels[tensor.weight_axis >= 0
                                      ? (i / inner) % tensor.channels.size()
                                      : i];
        const double value = std::round(
            tensor.constants[i] / static_cast<double>(encoding.scale) -
            encoding.offset);
        if (bytes == 1) {
          const int8_t q =
              static_cast<int8_t>(std::clamp(value, -127.0, 127.0));
          std::memcpy(tensor.quantized.data() + i, &q, 1);
        } else if (bytes == 2) {
          const uint16_t q =
              static_cast<uint16_t>(std::clamp(value, 0.0, 65535.0));
          std::memcpy(tensor.quantized.data() + i * 2, &q, 2);
        } else {
          if (value < -2147483648.0 || value > 2147483647.0)
            return fail("Calibrated convolution bias exceeds INT32 range");
          const int32_t q = static_cast<int32_t>(value);
          std::memcpy(tensor.quantized.data() + i * 4, &q, 4);
        }
      }
    }
    return true;
  }

  bool save(const std::string &path) {
    Qnn_ContextBinarySize_t size = 0, written = 0;
    if (!ok(api->contextGetBinarySize(context, &size), "W8A16 context size") ||
        size == 0 || size > 512u * 1024u * 1024u)
      return false;
    std::vector<uint8_t> bytes(size);
    if (!ok(api->contextGetBinary(context, bytes.data(), size, &written),
            "Serialize W8A16 context") ||
        written == 0 || written > size)
      return false;
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    file.write(reinterpret_cast<const char *>(bytes.data()), written);
    file.close();
    return file.good() || fail("Unable to save calibrated context");
  }

private:
  void *dlc_library = nullptr, *system_library = nullptr,
       *backend_library = nullptr;
};

std::vector<std::string> samples(const std::string &directory,
                                 const char *prefix) {
  std::vector<std::string> files;
  DIR *dir = opendir(directory.c_str());
  if (!dir)
    return files;
  while (const auto *entry = readdir(dir)) {
    const std::string name = entry->d_name;
    if (name.size() == std::strlen(prefix) + 7 &&
        name.compare(0, std::strlen(prefix), prefix) == 0 &&
        name.substr(name.size() - 4) == ".rgb" &&
        std::all_of(name.begin() + std::strlen(prefix), name.end() - 4,
                    [](char c) { return c >= '0' && c <= '9'; })) {
      files.push_back(directory + "/" + name);
    }
  }
  closedir(dir);
  std::sort(files.begin(), files.end());
  return files;
}
} // namespace

std::string prepare(const std::string &dlc, const std::string &directory,
                    const std::string &binary, const Progress &progress,
                    bool verify) {
  Quantizer quantizer;
  auto calibration = samples(directory, "input-");
  auto validation = samples(directory, "verify-");
  if (calibration.empty() || calibration.size() > 256 || validation.empty() ||
      validation.size() > 16)
    return "Invalid calibration/validation sample list";
  if (!progress(0, 0, calibration.size()))
    return "Calibration cancelled";
  if (!quantizer.open(dlc) || !quantizer.compose(false))
    return quantizer.error.empty() ? "Unable to initialize calibration"
                                   : quantizer.error;
  if (quantizer.input_shape != std::vector<uint32_t>({1, 256, 256, 3}))
    return "Calibration requires NHWC 256x256 RGB input";
  std::vector<float> values, result;
  for (size_t i = 0; i < calibration.size(); ++i) {
    if (!progress(0, i, calibration.size()))
      return "Calibration cancelled";
    if (!quantizer.read_image(calibration[i], values) ||
        !quantizer.execute_cpu(values, result))
      return quantizer.error;
  }
  std::vector<std::vector<float>> reference, validation_inputs;
  quantizer.collecting = false;
  for (size_t i = 0; i < validation.size(); ++i) {
    if (!progress(1, i, validation.size()))
      return "Calibration cancelled";
    if (!quantizer.read_image(validation[i], values) ||
        !quantizer.execute_cpu(values, result))
      return quantizer.error;
    validation_inputs.push_back(values);
    reference.push_back(result);
  }
  quantizer.close_backend();
  if (!progress(2, 0, 1))
    return "Calibration cancelled";
  if (!quantizer.encode() || !quantizer.compose(true) ||
      !quantizer.save(binary + ".partial"))
    return quantizer.error.empty() ? "Unable to compile calibrated graph"
                                   : quantizer.error;
  quantizer.close_backend();
  if (verify) {
    spatial_qnn::Runtime runtime;
    if (!runtime.load(binary + ".partial"))
      return runtime.error();
    for (size_t i = 0; i < validation.size(); ++i) {
      if (!progress(3, i, validation.size()))
        return "Calibration cancelled";
      std::vector<spatial_qnn::TensorData> outputs;
      if (!runtime.execute({{quantizer.input_name, quantizer.input_shape,
                             validation_inputs[i]}},
                           outputs))
        return runtime.error();
      if (outputs.size() != 1 ||
          outputs[0].values.size() != reference[i].size())
        return "W8A16 verification returned an invalid output shape";
      double squared_error = 0.0;
      for (size_t j = 0; j < reference[i].size(); ++j) {
        const double difference = outputs[0].values[j] - reference[i][j];
        if (!std::isfinite(difference))
          return "W8A16 verification produced nonfinite values";
        squared_error += difference * difference;
      }
      // 30 dB against the local float baseline is a minimum numerical guard,
      // not a claim about perceptual quality or every image a reader may open.
      if (squared_error / reference[i].size() > 0.001)
        return "W8A16 output differs too much from the original model";
    }
  }
  if (!progress(3, validation.size(), validation.size()))
    return "Calibration cancelled";
  if (std::rename((binary + ".partial").c_str(), binary.c_str()) != 0)
    return "Unable to commit calibrated context";
  return {};
}
} // namespace qnn_ondevice
#else
namespace qnn_ondevice {
std::string prepare(const std::string &, const std::string &,
                    const std::string &, const Progress &, bool) {
  return "On-device calibration requires an arm64 QNN build";
}
} // namespace qnn_ondevice
#endif
