// Host regression test; no Android/NCNN runtime is required:
// c++ -std=c++17 -O2 -Wall -Wextra -Werror monochrome_guard_test.cpp -o monochrome_guard_test
#include "../../main/cpp/monochrome_guard.h"

#include <array>
#include <cstdlib>
#include <iostream>
#include <vector>

using Pixel = std::array<unsigned char, 4>;

static void require(bool condition, const char *message) {
  if (!condition) {
    std::cerr << "FAIL: " << message << '\n';
    std::exit(1);
  }
}

static std::vector<unsigned char> page(int width, int height, Pixel pixel) {
  std::vector<unsigned char> rgba(static_cast<std::size_t>(width) * height * 4);
  for (std::size_t i = 0; i < rgba.size(); i += 4) {
    std::copy(pixel.begin(), pixel.end(), rgba.begin() + i);
  }
  return rgba;
}

static void preserves_colour(std::vector<unsigned char> rgba, int width,
                             int height, const char *message) {
  const auto original = rgba;
  require(!monochrome_guard::is_neutral_rgba(rgba.data(), width, height), message);
  require(rgba == original, "a rejected page must remain byte-for-byte intact");
}

int main() {
  // Cover the complete luma range, including black/white manga backgrounds.
  auto gray = page(256, 1, {0, 0, 0, 255});
  for (int x = 0; x < 256; ++x) {
    gray[x * 4] = gray[x * 4 + 1] = gray[x * 4 + 2] = x;
  }
  const auto original_gray = gray;
  require(monochrome_guard::is_neutral_rgba(gray.data(), 256, 1),
          "pure grayscale must still enable output colour correction");
  require(gray == original_gray, "source pixels must be unchanged");

  auto near_gray = page(7, 5, {100, 102, 101, 255});
  const auto original_near_gray = near_gray;
  require(monochrome_guard::is_neutral_rgba(near_gray.data(), 7, 5),
          "tiny RGB quantization differences should be tolerated");
  require(near_gray == original_near_gray,
          "classification must not require an input RGB-to-gray write pass");

  auto jpeg_noise = page(1024, 1024, {128, 128, 128, 255});
  for (std::size_t k = 0; k < 400; ++k) {
    jpeg_noise[(17 + k * 1973) * 4] = 131 + k % 6;
  }
  const auto original_jpeg_noise = jpeg_noise;
  require(monochrome_guard::is_neutral_rgba(jpeg_noise.data(), 1024, 1024),
          "sparse weak JPEG noise must not reject a grayscale manga page");
  require(jpeg_noise == original_jpeg_noise, "JPEG source pixels stay intact");

  auto sampled_noise = page(1024, 1024, {128, 128, 128, 255});
  for (std::size_t x = 8; x < 136; x += 16) {
    sampled_noise[(8 * 1024 + x) * 4] = 134;
  }
  require(monochrome_guard::is_neutral_rgba(sampled_noise.data(), 1024, 1024),
          "sparse noise aligned with the sample grid must remain grayscale");

  auto weak_accent = page(1024, 1024, {128, 128, 128, 255});
  for (std::size_t k = 0; k < 8; ++k) weak_accent[k * 4] = 133;
  preserves_colour(weak_accent, 1024, 1024,
                   "a cluster of weak colour must not be treated as sparse JPEG noise");
  auto widespread_noise = page(1024, 1024, {128, 128, 128, 255});
  for (std::size_t k = 0; k < 600; ++k) {
    widespread_noise[(17 + k * 1601) * 4] = 133;
  }
  preserves_colour(widespread_noise, 1024, 1024,
                   "weak chroma exceeding the noise budget must be preserved");

  preserves_colour(page(128, 128, {236, 228, 224, 255}), 128, 128,
                   "pastel artwork below the old chroma threshold is colour");
  preserves_colour(page(128, 128, {100, 110, 119, 255}), 128, 128,
                   "muted midtone artwork is colour");
  preserves_colour(page(128, 128, {180, 176, 171, 255}), 128, 128,
                   "uniform sepia artwork/source tint must be preserved");
  preserves_colour(page(128, 128, {100, 100, 103, 255}), 128, 128,
                   "even subtle source colour above decoder tolerance is preserved");

  auto accent = page(1024, 1024, {240, 240, 240, 255});
  accent[0] = 255;
  accent[1] = accent[2] = 0;
  preserves_colour(accent, 1024, 1024,
                   "a single coloured pixel missed by the old sampling grid survives");
  auto small_panel = page(1024, 1024, {240, 240, 240, 255});
  for (int y = 1; y < 65; ++y) {
    for (int x = 1; x < 65; ++x) {
      const auto i = (static_cast<std::size_t>(y) * 1024 + x) * 4;
      small_panel[i] = 255;
      small_panel[i + 1] = 80;
      small_panel[i + 2] = 100;
    }
  }
  preserves_colour(small_panel, 1024, 1024,
                   "a coloured panel covering less than 1% of a page survives");
  auto last_pixel = page(65, 67, {240, 240, 240, 255});
  last_pixel[last_pixel.size() - 4] = 0;
  preserves_colour(last_pixel, 65, 67,
                   "colour at an odd-sized image's last pixel is checked");

  preserves_colour(page(1, 1, {1, 0, 0, 1}), 1, 1,
                   "premultiplied translucent colour must not look neutral");
  auto transparent = page(2, 1, {32, 32, 32, 128});
  transparent[4] = 255;
  transparent[5] = transparent[6] = transparent[7] = 0;
  const auto original_transparent = transparent;
  require(monochrome_guard::is_neutral_rgba(transparent.data(), 2, 1),
          "invisible RGB must not veto visible grayscale");
  require(transparent == original_transparent,
          "alpha and invisible RGB must be preserved");
  auto invisible = page(2, 2, {0, 0, 0, 0});
  require(!monochrome_guard::is_neutral_rgba(invisible.data(), 2, 2),
          "an entirely invisible image gives no monochrome evidence");
  require(!monochrome_guard::is_neutral_rgba(nullptr, 1, 1), "null input");
  require(!monochrome_guard::is_neutral_rgba(gray.data(), 0, 1), "zero width");
  require(!monochrome_guard::is_neutral_rgba(gray.data(), 256, -1), "negative height");

  std::cout << "All monochrome guard regression cases passed\n";
}
