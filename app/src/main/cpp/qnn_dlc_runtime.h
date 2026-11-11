#ifndef MIHON_QNN_DLC_RUNTIME_H
#define MIHON_QNN_DLC_RUNTIME_H

#include <cstdint>
#include <string>
#include <vector>

namespace qnn_dlc {

struct TensorData {
  std::string name;
  std::vector<uint32_t> dimensions;
  std::vector<float> values;
};

class Runtime {
public:
  Runtime();
  ~Runtime();
  Runtime(const Runtime &) = delete;
  Runtime &operator=(const Runtime &) = delete;

  bool initialize();
  bool compile_dlc(const std::string &dlc_path,
                   const std::string &binary_path);
  bool load(const std::string &binary_path);
  bool execute(const std::vector<TensorData> &inputs,
               std::vector<TensorData> &outputs);
  void unload_graph();
  void reset();

  const std::vector<TensorData> &input_metadata() const;
  const std::vector<TensorData> &output_metadata() const;
  const std::string &error() const;

private:
  class Impl;
  Impl *impl_;
};

} // namespace qnn_dlc

#endif
