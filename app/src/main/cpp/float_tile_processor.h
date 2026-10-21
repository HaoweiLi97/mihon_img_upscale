#ifndef MIHON_FLOAT_TILE_PROCESSOR_H
#define MIHON_FLOAT_TILE_PROCESSOR_H
#include "periodic_texture_guard.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <vector>

namespace float_tiles {
inline int reflect(int coordinate, int size) {
  if (size <= 1) return 0;
  while (coordinate < 0 || coordinate >= size)
    coordinate = coordinate < 0 ? -coordinate : 2 * size - 2 - coordinate;
  return coordinate;
}

// Same tile borders, texture protection and alpha handling as the QNN path.
template <typename Run>
int process(const uint8_t *input, int width, int height, int input_stride,
            uint8_t *output, int output_stride, int scale, int padding,
            periodic_texture_guard::Analysis &analysis,
            std::atomic<int> *progress, const std::atomic<bool> *abort, Run run) {
  constexpr int tile = 256;
  const int core = tile - 2 * padding, output_tile = tile * scale;
  if (!input || !output || width <= 0 || height <= 0 || core <= 0 ||
      (scale != 2 && scale != 3)) return -1;
  const auto &plan = analysis.plan(core);
  const periodic_texture_guard::AxisTransform guard(tile);
  const int total = ((width + core - 1) / core) * ((height + core - 1) / core);
  int completed = 0;
  std::vector<float> in(tile * tile * 3), out(output_tile * output_tile * 3);
  for (int ty = 0; ty < height; ty += core) {
    for (int tx = 0; tx < width; tx += core) {
      if (abort && abort->load()) return -2;
      const bool guarded = plan.guards(tx, ty);
      auto pixel = [&](int x, int y, int c) {
        return input[static_cast<size_t>(reflect(y, height)) * input_stride +
                     static_cast<size_t>(reflect(x, width)) * 4 + c] / 255.0f;
      };
      for (int y = 0; y < tile; ++y) for (int x = 0; x < tile; ++x) {
        for (int c = 0; c < 3; ++c) {
          float v;
          if (!guarded) {
            v = pixel(tx - padding + x, ty - padding + y, c);
          } else if (x < guard.border || y < guard.border ||
                     x >= guard.border + guard.scaled_length ||
                     y >= guard.border + guard.scaled_length) {
            v = 1;
          } else {
            float sx = tx - padding + guard.source_coordinate(x);
            float sy = ty - padding + guard.source_coordinate(y);
            int x0 = static_cast<int>(std::floor(sx)), y0 = static_cast<int>(std::floor(sy));
            float fx = sx - x0, fy = sy - y0;
            v = (pixel(x0, y0, c) * (1-fx) + pixel(x0+1, y0, c) * fx) * (1-fy) +
                (pixel(x0, y0+1, c) * (1-fx) + pixel(x0+1, y0+1, c) * fx) * fy;
          }
          in[(y * tile + x) * 3 + c] = v;
        }
      }
      if (!run(in, out)) return -1;
      if (out.size() != static_cast<size_t>(output_tile * output_tile * 3)) return -1;
      for (float value : out) if (!std::isfinite(value)) return -1;
      if (abort && abort->load()) return -2;
      auto sample = [&](int x, int y, int c) {
        return out[(static_cast<size_t>(y) * output_tile + x) * 3 + c];
      };
      const int cw = std::min(core, width - tx) * scale;
      const int ch = std::min(core, height - ty) * scale;
      for (int y = 0; y < ch; ++y) {
        uint8_t *row = output + static_cast<size_t>(ty * scale + y) * output_stride + tx * scale * 4;
        for (int x = 0; x < cw; ++x) {
          for (int c = 0; c < 3; ++c) {
            float v;
            if (!guarded) v = sample(padding * scale + x, padding * scale + y, c);
            else {
              float sx = guard.restored_output_coordinate(x, padding, scale);
              float sy = guard.restored_output_coordinate(y, padding, scale);
              int x0 = std::clamp(static_cast<int>(std::floor(sx)), 0, output_tile - 1);
              int y0 = std::clamp(static_cast<int>(std::floor(sy)), 0, output_tile - 1);
              int x1 = std::min(x0+1, output_tile-1), y1 = std::min(y0+1, output_tile-1);
              float fx = sx - std::floor(sx), fy = sy - std::floor(sy);
              v = (sample(x0,y0,c)*(1-fx) + sample(x1,y0,c)*fx)*(1-fy) +
                  (sample(x0,y1,c)*(1-fx) + sample(x1,y1,c)*fx)*fy;
            }
            row[x*4+c] = static_cast<uint8_t>(std::clamp(v, 0.0f, 1.0f) * 255 + 0.5f);
          }
          row[x*4+3] = input[static_cast<size_t>(std::min(height-1, ty+y/scale)) * input_stride +
                                std::min(width-1, tx+x/scale) * 4 + 3];
        }
      }
      if (progress) progress->store(++completed * 100 / total);
    }
  }
  return 0;
}
} // namespace float_tiles
#endif
