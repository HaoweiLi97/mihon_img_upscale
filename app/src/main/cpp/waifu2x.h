// waifu2x implemented with ncnn library

#ifndef WAIFU2X_H
#define WAIFU2X_H

#include <atomic>
#include <string>

// ncnn
#include "gpu.h"
#include "layer.h"
#include "net.h"

class Waifu2x {
public:
  Waifu2x(int gpuid, bool tta_mode = false, int num_threads = 1);
  ~Waifu2x();

  int load(const std::string &parampath, const std::string &modelpath);

  int process(const ncnn::Mat &inimage, ncnn::Mat &outimage) const;
  int process_cpu(const ncnn::Mat &inimage, ncnn::Mat &outimage) const;

public:
  // waifu2x parameters
  int noise;
  int scale;
  int tilesize;
  int prepadding;
  std::atomic<int> *progress_ptr = nullptr;
  std::atomic<int> *ui_busy_ptr = nullptr;
  std::atomic<bool> *should_abort_ptr = nullptr;
  int tile_sleep_ms = 0; // Sleep between tiles for cooling (0 = full speed)
  bool is_snapdragon = false;

private:
  ncnn::VulkanDevice *vkdev;
  ncnn::Net net;
  ncnn::Pipeline *waifu2x_preproc;
  ncnn::Pipeline *waifu2x_postproc;
  ncnn::Pipeline *waifu2x_preproc_tta;
  ncnn::Pipeline *waifu2x_postproc_tta;
  ncnn::Layer *bicubic_2x;
  bool tta_mode;
};

#endif // WAIFU2X_H
