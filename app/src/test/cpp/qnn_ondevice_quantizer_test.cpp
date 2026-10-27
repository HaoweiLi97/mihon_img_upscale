// SDK integration test. Define MIHON_QNN_HOST_TEST and MIHON_ENABLE_QNN=1.
// The same calibration/encoding implementation is compiled into Android.
#include "../../main/cpp/qnn_ondevice_quantizer.h"
#include <cstdio>
#include <fstream>
#include <string>

int main(int argc, char **argv) {
  if (argc != 5)
    return 2;
  const bool cancel = std::string(argv[4]) == "cancel";
  const auto error = qnn_ondevice::prepare(
      argv[1], argv[2], argv[3], [cancel](int, int, int) { return !cancel; },
      false);
  if (cancel) {
    std::ifstream committed(argv[3], std::ios::binary);
    if (committed.good() || error != "Calibration cancelled")
      return 1;
    std::puts("PASS: cancellation leaves no committed context");
    return 0;
  }
  if (!error.empty()) {
    std::fprintf(stderr, "FAIL: %s\n", error.c_str());
    return 1;
  }
  std::ifstream binary(argv[3], std::ios::binary | std::ios::ate);
  if (!binary.good() || binary.tellg() <= 0)
    return 1;
  std::puts(
      "PASS: calibration, W8A16 quantization and target context serialization");
}
