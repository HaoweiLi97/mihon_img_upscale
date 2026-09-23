#ifndef MIHON_PERIODIC_TEXTURE_GUARD_H
#define MIHON_PERIODIC_TEXTURE_GUARD_H

#include <algorithm>
#include <cmath>

namespace periodic_texture_guard {

constexpr int kCellSize = 16;
constexpr int kGradientThreshold = 24;
constexpr int kDominantGradient = 30;
constexpr int kDirectionRatio = 4;
constexpr int kEdgeDensityPercent = 18;
constexpr int kRiskyCellPercent = 50;
constexpr int kShrinkNumerator = 3;
constexpr int kShrinkDenominator = 4;

// Detect a large, nearly one-dimensional screentone. Ordinary line art may
// contain a few directional cells, but the Real-CUGAN failure needs that
// texture to occupy roughly half of the model core.
template <typename Sample>
bool occupies_too_much(int width, int height, Sample sample,
                       int *risky_cells_out = nullptr,
                       int *total_cells_out = nullptr) {
  if (width < 8 || height < 8) return false;
  int risky_cells = 0;
  int total_cells = 0;
  for (int y0 = 0; y0 < height; y0 += kCellSize) {
    const int y1 = std::min(height, y0 + kCellSize);
    for (int x0 = 0; x0 < width; x0 += kCellSize) {
      const int x1 = std::min(width, x0 + kCellSize);
      long gradient_x = 0;
      long gradient_y = 0;
      int edges_x = 0;
      int edges_y = 0;
      int samples = 0;
      for (int y = std::max(1, y0); y < y1; ++y) {
        for (int x = std::max(1, x0); x < x1; ++x) {
          const int center = sample(x, y);
          const int gx = std::abs(center - sample(x - 1, y));
          const int gy = std::abs(center - sample(x, y - 1));
          gradient_x += gx;
          gradient_y += gy;
          edges_x += gx >= kGradientThreshold;
          edges_y += gy >= kGradientThreshold;
          ++samples;
        }
      }
      if (samples == 0) continue;
      const int gx = static_cast<int>(gradient_x / samples);
      const int gy = static_cast<int>(gradient_y / samples);
      const int density_x = edges_x * 100 / samples;
      const int density_y = edges_y * 100 / samples;
      const bool horizontal =
          gy >= kDominantGradient &&
          gy >= std::max(1, gx) * kDirectionRatio &&
          density_y >= kEdgeDensityPercent;
      const bool vertical =
          gx >= kDominantGradient &&
          gx >= std::max(1, gy) * kDirectionRatio &&
          density_x >= kEdgeDensityPercent;
      risky_cells += horizontal || vertical;
      ++total_cells;
    }
  }
  if (risky_cells_out) *risky_cells_out = risky_cells;
  if (total_cells_out) *total_cells_out = total_cells;
  return risky_cells >= 4 &&
         risky_cells * 100 >= total_cells * kRiskyCellPercent;
}

inline float source_coordinate(int destination, int border) {
  return (static_cast<float>(destination - border) + 0.5f) *
             kShrinkDenominator / kShrinkNumerator -
         0.5f;
}

inline float restored_output_coordinate(int destination, int border,
                                        int padding, int scale) {
  return border * scale +
         (padding * scale + destination + 0.5f) *
             kShrinkNumerator / kShrinkDenominator -
         0.5f;
}

}  // namespace periodic_texture_guard

#endif  // MIHON_PERIODIC_TEXTURE_GUARD_H
