#ifndef MIHON_PERIODIC_TEXTURE_GUARD_H
#define MIHON_PERIODIC_TEXTURE_GUARD_H

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <utility>
#include <vector>

namespace periodic_texture_guard {

constexpr int kCellSize = 16;
constexpr int kGradientThreshold = 24;
constexpr int kDominantGradient = 30;
constexpr int kDirectionRatio = 4;
constexpr int kEdgeDensityPercent = 18;
// Require repeated edges across most of the orthogonal axis so localized
// clusters of short strokes cannot trigger a whole-tile resize.
constexpr int kDirectionalCoveragePercent = 70;
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
  const int expected_cells = ((width + kCellSize - 1) / kCellSize) *
                             ((height + kCellSize - 1) / kCellSize);
  for (int y0 = 0; y0 < height; y0 += kCellSize) {
    const int y1 = std::min(height, y0 + kCellSize);
    for (int x0 = 0; x0 < width; x0 += kCellSize) {
      const int x1 = std::min(width, x0 + kCellSize);
      long gradient_x = 0;
      long gradient_y = 0;
      int edges_x = 0;
      int edges_y = 0;
      int column_edges_y[kCellSize] = {};
      int row_edges_x[kCellSize] = {};
      int samples = 0;
      const int sample_x0 = std::max(1, x0);
      const int sample_y0 = std::max(1, y0);
      const int sample_width = x1 - sample_x0;
      const int sample_height = y1 - sample_y0;
      for (int y = sample_y0; y < y1; ++y) {
        for (int x = sample_x0; x < x1; ++x) {
          const int center = sample(x, y);
          const int gx = std::abs(center - sample(x - 1, y));
          const int gy = std::abs(center - sample(x, y - 1));
          gradient_x += gx;
          gradient_y += gy;
          edges_x += gx >= kGradientThreshold;
          edges_y += gy >= kGradientThreshold;
          row_edges_x[y - y0] += gx >= kGradientThreshold;
          column_edges_y[x - x0] += gy >= kGradientThreshold;
          ++samples;
        }
      }
      if (samples == 0) continue;
      int active_columns_y = 0;
      int active_rows_x = 0;
      for (int x = sample_x0; x < x1; ++x) {
        active_columns_y += column_edges_y[x - x0] * 100 >=
                            sample_height * kEdgeDensityPercent;
      }
      for (int y = sample_y0; y < y1; ++y) {
        active_rows_x += row_edges_x[y - y0] * 100 >=
                         sample_width * kEdgeDensityPercent;
      }
      const int gx = static_cast<int>(gradient_x / samples);
      const int gy = static_cast<int>(gradient_y / samples);
      const int density_x = edges_x * 100 / samples;
      const int density_y = edges_y * 100 / samples;
      const bool horizontal =
          gy >= kDominantGradient &&
          gy >= std::max(1, gx) * kDirectionRatio &&
          density_y >= kEdgeDensityPercent &&
          active_columns_y * 100 >= sample_width * kDirectionalCoveragePercent;
      const bool vertical =
          gx >= kDominantGradient &&
          gx >= std::max(1, gy) * kDirectionRatio &&
          density_x >= kEdgeDensityPercent &&
          active_rows_x * 100 >= sample_height * kDirectionalCoveragePercent;
      risky_cells += horizontal || vertical;
      ++total_cells;
      // Callers requesting diagnostics still receive complete counts. For a
      // boolean decision, stop as soon as remaining cells cannot change it.
      if (!risky_cells_out && !total_cells_out) {
        if (risky_cells >= 4 &&
            risky_cells * 100 >= expected_cells * kRiskyCellPercent) return true;
        const int maximum_risky = risky_cells + expected_cells - total_cells;
        if (maximum_risky < 4 ||
            maximum_risky * 100 < expected_cells * kRiskyCellPercent) return false;
      }
    }
  }
  if (risky_cells_out) *risky_cells_out = risky_cells;
  if (total_cells_out) *total_cells_out = total_cells;
  return risky_cells >= 4 &&
         risky_cells * 100 >= total_cells * kRiskyCellPercent;
}

struct TilePlan {
  int core = 0;
  int columns = 0;
  int rows = 0;
  bool any_risky = false;
  std::vector<uint8_t> risky;

  bool guards(int x, int y) const {
    if (core <= 0 || x < 0 || y < 0 || x / core >= columns ||
        y / core >= rows || risky.empty()) return false;
    return risky[static_cast<std::size_t>(y / core) * columns + x / core] != 0;
  }
};

// Per-inference cache. Build only the layout that is actually used, directly
// from the immutable source. A backend fallback with a different core needs a
// separate plan: reusing its tile decisions would cover different image areas.
class Analysis {
public:
  Analysis(const uint8_t *rgba, int width, int height, int stride, bool enabled)
      : rgba_(rgba), width_(width), height_(height), stride_(stride),
        enabled_(enabled && rgba && width > 0 && height > 0 &&
                 stride >= static_cast<std::int64_t>(width) * 4) {}

  const TilePlan &plan(int core) {
    for (const auto &cached : plans_) {
      if (cached.core == core) return cached;
    }
    TilePlan result;
    result.core = core;
    if (enabled_ && core > 0) {
      result.columns = 1 + (width_ - 1) / core;
      result.rows = 1 + (height_ - 1) / core;
      result.risky.resize(static_cast<std::size_t>(result.columns) * result.rows);
      for (int yi = 0; yi < result.rows; ++yi) {
        for (int xi = 0; xi < result.columns; ++xi) {
          const int x0 = xi * core;
          const int y0 = yi * core;
          const bool risk = occupies_too_much(
              std::min(core, width_ - x0), std::min(core, height_ - y0),
              [&](int x, int y) {
                const uint8_t *pixel = rgba_ +
                    static_cast<std::size_t>(y0 + y) * stride_ +
                    static_cast<std::size_t>(x0 + x) * 4;
                return (77 * pixel[0] + 150 * pixel[1] + 29 * pixel[2] + 128) >> 8;
              });
          result.risky[static_cast<std::size_t>(yi) * result.columns + xi] = risk;
          result.any_risky |= risk;
        }
      }
    }
    plans_.push_back(std::move(result));
    return plans_.back();
  }

private:
  const uint8_t *rgba_;
  int width_;
  int height_;
  int stride_;
  bool enabled_;
  std::deque<TilePlan> plans_;
};

// Both backends use the same pixel-center mapping. Edge tiles need the actual
// rounded resize ratio: restoring at exactly 3/4 would shift their crop whenever
// the padded source length is not a multiple of four. Length must be positive.
struct AxisTransform {
  explicit AxisTransform(int length)
      : scaled_length(std::max(1, length * kShrinkNumerator / kShrinkDenominator)),
        border((length - scaled_length) / 2),
        shrink_ratio(static_cast<float>(scaled_length) / length),
        inverse_ratio(static_cast<float>(length) / scaled_length) {}

  float source_coordinate(int destination) const {
    return (destination - border + 0.5f) * inverse_ratio - 0.5f;
  }

  float restored_output_coordinate(int destination, int padding, int scale) const {
    return border * scale + (padding * scale + destination + 0.5f) *
                                shrink_ratio - 0.5f;
  }

  int scaled_length;
  int border;
  float shrink_ratio;
  float inverse_ratio;
};

}  // namespace periodic_texture_guard

#endif  // MIHON_PERIODIC_TEXTURE_GUARD_H
