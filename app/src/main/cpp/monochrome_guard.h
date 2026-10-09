#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace monochrome_guard {

// Tolerate tiny RGB differences and sparse, weak compression noise in grayscale.
// A tinted scan and deliberately muted artwork cannot reliably be distinguished
// from RGB alone, so preserve both. Sampling quickly rejects ordinary colour
// pages; only neutral candidates need exhaustive validation. Sampling alone or
// a page-wide colour quota can miss small coloured panels, text and accents.
inline int pixel_chroma(const unsigned char *pixel) {
  return std::max({pixel[0], pixel[1], pixel[2]}) -
         std::min({pixel[0], pixel[1], pixel[2]});
}

// Called only for suspicious NEON blocks (or the portable fallback). A few
// isolated 3..8-level errors can be JPEG noise; clusters or stronger colour
// must survive. Never grant strong colours a page-wide percentage allowance.
inline bool check_block(const unsigned char *rgba, std::size_t count,
                        std::size_t noise_limit, std::size_t &noise_pixels,
                        bool &has_visible_pixels) {
  int block_noise = 0;
  for (std::size_t i = 0; i < count; ++i) {
    const unsigned char *pixel = rgba + i * 4;
    if (pixel[3] == 0) continue;
    has_visible_pixels = true;
    const int chroma = pixel_chroma(pixel);
    // Premultiplied alpha can hide saturated colour in a tiny RGB difference.
    if (pixel[3] != 255) {
      if (chroma != 0) return false;
    } else if (chroma > 2) {
      if (chroma > 8 || ++block_noise > 2 || ++noise_pixels > noise_limit) {
        return false;
      }
    }
  }
  return true;
}

inline bool is_neutral_rgba(const unsigned char *rgba, int width, int height) {
  if (!rgba || width <= 0 || height <= 0) return false;

  constexpr int sample_axis = 64;
  const int step_x = 1 + (width - 1) / sample_axis;
  const int step_y = 1 + (height - 1) / sample_axis;
  std::size_t samples = 0;
  std::size_t weak_samples = 0;
  for (int y = step_y / 2; y < height; y += step_y) {
    for (int x = step_x / 2; x < width; x += step_x) {
      const auto index = (static_cast<std::size_t>(y) * width + x) * 4;
      const unsigned char *pixel = rgba + index;
      if (pixel[3] == 0) continue;
      ++samples;
      const int chroma = pixel_chroma(pixel);
      if (pixel[3] != 255 && chroma != 0) return false;
      if (chroma > 8) return false;
      if (chroma > 2) ++weak_samples;
    }
  }
  // This is only a fast rejection of widespread weak colour. Use a generous
  // sample allowance: a few noise pixels can happen to align with the grid.
  // The full check below measures the actual 0.05% budget and local density.
  if (weak_samples > std::max<std::size_t>(4, samples / 100)) return false;

  const std::size_t pixel_count = static_cast<std::size_t>(width) * height;
  const std::size_t noise_limit = std::max<std::size_t>(1, pixel_count / 2000);
  std::size_t noise_pixels = 0;
  bool has_visible_pixels = false;
  std::size_t i = 0;
#if defined(__ARM_NEON)
  const uint8x16_t zero = vdupq_n_u8(0);
  const uint8x16_t two = vdupq_n_u8(2);
  const uint8x16_t opaque = vdupq_n_u8(255);
  for (; i + 16 <= pixel_count; i += 16) {
    const uint8x16x4_t pixels = vld4q_u8(rgba + i * 4);
    const uint8x16_t min_rgb =
        vminq_u8(vminq_u8(pixels.val[0], pixels.val[1]), pixels.val[2]);
    const uint8x16_t max_rgb =
        vmaxq_u8(vmaxq_u8(pixels.val[0], pixels.val[1]), pixels.val[2]);
    const uint8x16_t visible = vcgtq_u8(pixels.val[3], zero);
    const uint8x16_t tolerance =
        vandq_u8(vceqq_u8(pixels.val[3], opaque), two);
    const uint64x2_t coloured = vreinterpretq_u64_u8(vandq_u8(
        vcgtq_u8(vsubq_u8(max_rgb, min_rgb), tolerance), visible));
    if (vgetq_lane_u64(coloured, 0) | vgetq_lane_u64(coloured, 1)) {
      if (!check_block(rgba + i * 4, 16, noise_limit, noise_pixels,
                       has_visible_pixels)) {
        return false;
      }
    }
    const uint64x2_t visible_bits = vreinterpretq_u64_u8(visible);
    has_visible_pixels |=
        (vgetq_lane_u64(visible_bits, 0) | vgetq_lane_u64(visible_bits, 1)) != 0;
  }
#elif defined(_WIN32) || (defined(__BYTE_ORDER__) && \
                         __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
  // Portable little-endian fast path for ordinary R=G=B pages. Checking packed
  // pairs avoids paying scalar chroma/noise bookkeeping on every gray pixel.
  // memcpy permits unaligned input without aliasing or alignment assumptions.
  for (; i + 16 <= pixel_count; i += 16) {
    std::uint64_t differences = 0;
    std::uint64_t alpha = 0;
    for (std::size_t offset = 0; offset < 16; offset += 2) {
      std::uint64_t pair;
      std::memcpy(&pair, rgba + (i + offset) * 4, sizeof(pair));
      differences |= (pair ^ (pair >> 8)) & 0x0000ffff0000ffffULL;
      alpha |= pair & 0xff000000ff000000ULL;
    }
    if (differences != 0 &&
        !check_block(rgba + i * 4, 16, noise_limit, noise_pixels,
                     has_visible_pixels)) {
      return false;
    }
    has_visible_pixels |= alpha != 0;
  }
#endif
  for (; i < pixel_count; i += 16) {
    if (!check_block(rgba + i * 4, std::min<std::size_t>(16, pixel_count - i),
                     noise_limit, noise_pixels, has_visible_pixels)) {
      return false;
    }
  }
  return has_visible_pixels;
}

}  // namespace monochrome_guard
