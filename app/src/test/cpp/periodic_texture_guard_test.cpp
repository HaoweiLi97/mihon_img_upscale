// Host regression test; no Android/NCNN runtime is required:
// c++ -std=c++17 -O2 -Wall -Wextra -Werror periodic_texture_guard_test.cpp -o periodic_texture_guard_test
#include "../../main/cpp/periodic_texture_guard.h"

#include <array>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <random>
#include <vector>

static void require(bool condition, const char *message) {
  if (!condition) {
    std::cerr << "FAIL: " << message << '\n';
    std::exit(1);
  }
}

template <typename Pixel>
static bool detect(Pixel pixel, int width = 128, int height = 128) {
  return periodic_texture_guard::occupies_too_much(width, height, pixel);
}

int main() {
  using namespace periodic_texture_guard;
  require(detect([](int, int y) { return (y / 2) % 2 ? 255 : 0; }),
          "dense horizontal screentone is risky");
  require(detect([](int x, int) { return (x / 2) % 2 ? 255 : 0; }),
          "dense vertical screentone is risky");
  require(!detect([](int, int) { return 128; }),
          "flat gray artwork is safe");
  for (int thickness : {1, 2, 3, 4, 5}) {
    for (int phase = 0; phase < thickness * 2; ++phase) {
      require(detect([=](int, int y) {
                return ((y + phase) / thickness) % 2 ? 255 : 0;
              }), "horizontal screentone survives line width and phase changes");
      require(detect([=](int x, int) {
                return ((x + phase) / thickness) % 2 ? 255 : 0;
              }), "vertical screentone survives line width and phase changes");
    }
  }
  require(detect([](int, int y) { return (y / 2) % 2 ? 192 : 64; }),
          "gray screentone does not require pure black and white");
  require(!detect([](int x, int y) {
            return x % 16 < 9 && y % 2 == 0 ? 0 : 255;
          }), "clusters of short horizontal strokes must not soften a whole tile");
  require(!detect([](int x, int y) {
            return y % 16 < 9 && x % 2 == 0 ? 0 : 255;
          }), "clusters of short vertical strokes must not soften a whole tile");
  require(!detect([](int x, int y) {
            return x % 64 < 2 || y % 64 < 2 ? 0 : 255;
          }), "panel borders are safe");
  require(!detect([](int x, int y) { return ((x + y) / 2) % 2 ? 255 : 0; }),
          "diagonal hatching is not a one-dimensional axis-aligned tone");
  require(!detect([](int x, int y) { return (x / 2 + y / 2) % 2 ? 255 : 0; }),
          "two-dimensional checker texture is safe");
  for (int percent : {25, 50, 75}) {
    require(detect([=](int x, int y) {
              return x < 128 * percent / 100 ? ((y / 2) % 2 ? 255 : 0) : 128;
            }) == (percent >= 50),
            "only tones occupying at least half of the tile trigger the guard");
  }

  // The accelerated decision must match complete counts across occupancy
  // thresholds, tiny tail cells and non-multiple-of-16 core dimensions.
  for (int width : {8, 17, 63, 128, 220}) {
    for (int height : {8, 17, 65, 128, 220}) {
      for (int percent : {0, 25, 49, 50, 51, 75, 100}) {
        std::mt19937 random(width * 1000 + height * 10 + percent);
        std::vector<uint8_t> cells(((width + 15) / 16) * ((height + 15) / 16));
        for (auto &cell : cells) cell = random() % 100 < static_cast<unsigned>(percent);
        const auto pixel = [&](int x, int y) {
          return cells[(y / 16) * ((width + 15) / 16) + x / 16]
                     ? ((y / 2) % 2 ? 255 : 0) : 128;
        };
        int risky = 0;
        int total = 0;
        const bool full = occupies_too_much(width, height, pixel, &risky, &total);
        require(full == occupies_too_much(width, height, pixel),
                "early exit must match the complete occupancy decision");
      }
    }
  }
  int reads = 0;
  detect([&](int, int) { ++reads; return 128; });
  require(reads < 128 * 128 * 3,
          "safe tiles stop before scanning every pixel");

  std::vector<uint8_t> rgba(128 * 128 * 4, 255);
  for (int y = 0; y < 128; ++y) {
    for (int x = 0; x < 128; ++x) {
      const uint8_t value = (y / 2) % 2 ? 255 : 0;
      const std::size_t i = (static_cast<std::size_t>(y) * 128 + x) * 4;
      rgba[i] = rgba[i + 1] = rgba[i + 2] = value;
    }
  }

  Analysis analysis(rgba.data(), 128, 128, 128 * 4, true);
  const TilePlan &first = analysis.plan(64);
  require(first.columns == 2 && first.rows == 2 && first.any_risky,
          "plan uses the backend core dimensions");
  require(first.guards(0, 0) && first.guards(63, 63) &&
              first.guards(64, 0) && first.guards(0, 64),
          "tile lookup covers every matching tile origin");
  require(&analysis.plan(64) == &first,
          "same backend layout reuses its detection results");
  const TilePlan &other_layout = analysis.plan(128);
  require(other_layout.columns == 1 && other_layout.rows == 1 &&
              other_layout.any_risky,
          "fallback backend gets a plan for its own core size");
  require(&analysis.plan(64) == &first && first.guards(64, 64),
          "cached plans remain valid after another backend layout is added");
  require(!first.guards(-1, 0) && !first.guards(128, 0),
          "lookup rejects coordinates outside the plan");

  constexpr int width = 137;
  constexpr int height = 73;
  constexpr int stride = width * 4 + 20;
  std::vector<uint8_t> padded(static_cast<std::size_t>(stride) * height, 255);
  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      const uint8_t value = x < 64 ? ((y / 2) % 2 ? 255 : 0) : 128;
      const auto i = static_cast<std::size_t>(y) * stride + x * 4;
      padded[i] = padded[i + 1] = padded[i + 2] = value;
    }
  }
  const auto original = padded;
  Analysis edge_analysis(padded.data(), width, height, stride, true);
  const auto &edge_plan = edge_analysis.plan(64);
  require(edge_plan.columns == 3 && edge_plan.rows == 2 &&
              edge_plan.guards(0, 0) && !edge_plan.guards(64, 0) &&
              !edge_plan.guards(128, 64),
          "row stride and partial edge tiles use the correct image region");
  require(padded == original, "analysis leaves source RGBA bytes intact");

  Analysis disabled(rgba.data(), 128, 128, 128 * 4, false);
  require(!disabled.plan(64).any_risky && !disabled.plan(64).guards(0, 0),
          "disabled guard adds no guarded tiles");
  Analysis invalid_stride(rgba.data(), 128, 128, 128, true);
  require(!invalid_stride.plan(64).any_risky,
          "invalid RGBA row stride fails closed");

  // A linear ramp survives bilinear sampling exactly. Simulate resizing, a
  // scale-factor model output, then restoration to catch phase shifts and
  // white-border leakage, especially for odd partial tiles.
  for (int core : {1, 7, 17, 63, 64, 127, 128, 220, 221}) {
    for (int padding : {14, 18}) {
      const int length = core + 2 * padding;
      const AxisTransform axis(length);
      require(axis.scaled_length == length * 3 / 4,
              "guard keeps the 75 percent shrink size");
      require(axis.border >= 0 && axis.border + axis.scaled_length <= length,
              "shrunk content fits inside the padded inference tile");
      for (int scale : {2, 3, 4}) {
        std::vector<float> inference(length * scale, 255.0f);
        for (int i = axis.border * scale;
             i < (axis.border + axis.scaled_length) * scale; ++i) {
          const float resized = (i + 0.5f) / scale - 0.5f;
          const int lo = static_cast<int>(std::floor(resized));
          const float fraction = resized - lo;
          inference[i] = axis.source_coordinate(lo) * (1.0f - fraction) +
                         axis.source_coordinate(lo + 1) * fraction;
        }
        for (int destination = 0; destination < core * scale; ++destination) {
          const float restored =
              axis.restored_output_coordinate(destination, padding, scale);
          const int lo = static_cast<int>(std::floor(restored));
          require(lo >= axis.border * scale &&
                      lo + 1 < (axis.border + axis.scaled_length) * scale,
                  "restoration samples content rather than the white border");
          const float fraction = restored - lo;
          const float value = inference[lo] * (1.0f - fraction) +
                              inference[lo + 1] * fraction;
          const float expected = padding + (destination + 0.5f) / scale - 0.5f;
          require(std::abs(value - expected) < 0.0001f,
                  "restored ramp retains its original pixel alignment");
        }
      }
    }
  }
  std::cout << "All periodic texture guard regression cases passed\n";
  return 0;
}
