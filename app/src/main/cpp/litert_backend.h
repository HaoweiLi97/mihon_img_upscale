#ifndef MIHON_LITERT_BACKEND_H
#define MIHON_LITERT_BACKEND_H
#include "periodic_texture_guard.h"
#include <atomic>
#include <cstdint>
#include <string>
#include <vector>
namespace litert_backend {
bool initialize(const std::string &model, const std::string &cache,
                const std::string &libraries, int scale, int padding,
                bool cpu_test_only = false);
std::string prepare(const std::string &model, const std::string &cache, const std::string &libraries, int scale, int padding);
bool is_initialized();
bool is_npu_active();
std::string last_error();
bool run_tile(const std::vector<float> &input, std::vector<float> &output);
int process_rgba(const uint8_t *input, int width, int height, int input_stride,
                 uint8_t *output, int output_stride,
                 periodic_texture_guard::Analysis &analysis,
                 std::atomic<int> *progress, const std::atomic<bool> *abort);
void shutdown();
}
#endif
