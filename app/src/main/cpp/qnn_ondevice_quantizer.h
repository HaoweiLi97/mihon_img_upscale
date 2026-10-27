#ifndef MIHON_QNN_ONDEVICE_QUANTIZER_H
#define MIHON_QNN_ONDEVICE_QUANTIZER_H

#include <functional>
#include <string>

namespace qnn_ondevice {
// Returns false from progress to cancel between calibration images / stages.
using Progress = std::function<bool(int stage, int completed, int total)>;
// Calibration, parameter quantization and target compilation all run locally.
// verify=false is reserved for host SDK integration tests without an HTP.
std::string prepare(const std::string &dlc, const std::string &samples,
                    const std::string &binary, const Progress &progress,
                    bool verify = true);
} // namespace qnn_ondevice

#endif
