# Mihon_img_upscale

English | [简体中文](README.zh-CN.md)

A [Mihon](https://github.com/mihonapp/mihon) fork with AI image enhancement, Vulkan GPU acceleration, Qualcomm and MediaTek NPU backends, and book page turns.

[Download the latest release](https://github.com/HaoweiLi97/mihon_img_upscale/releases/latest) · [Changelog](CHANGELOG.md) · [Report an issue](https://github.com/HaoweiLi97/mihon_img_upscale/issues)

<p align="center">
  <img width="350" alt="Mihon AI image enhancement preview" src="https://github.com/user-attachments/assets/c5d68b57-d93d-4a6e-b426-b8624ebcc5e8">
</p>

## Installation

Requires **Android 8.0 or newer**. Download an APK from this repository's [Releases](https://github.com/HaoweiLi97/mihon_img_upscale/releases):

| Package | Devices |
| :--- | :--- |
| `app-arm64-v8a-release.apk` | Most modern phones and tablets; choose this package for NPU processing |
| `app-armeabi-v7a-release.apk` | Older 32-bit ARM devices |
| `app-x86-release.apk` | 32-bit x86 devices and emulators |
| `app-x86_64-release.apk` | 64-bit x86 devices and emulators |
| `app-universal-release.apk` | All architectures; larger download, suitable when unsure |

Official releases from this repository use the same signing certificate and support in-place upgrades. Upstream Mihon and other forks use different certificates and cannot be replaced directly by this APK.

## Image enhancement

Enable **Image enhancement** in the reader settings, then select a processing backend, model, and scale. Separate options control the input processing resolution, the resolution above which enhancement is skipped, and the preload range.

- Visible pages take priority over nearby preloaded pages.
- Enhanced images are cached locally to avoid repeated processing; ink filter settings are included in cache identity.
- Status labels show queued work, enhancement progress, and completion.
- NPU preparation or inference failure keeps the original image available for reading.

### Processing backends

| Backend | Details |
| :--- | :--- |
| **Vulkan GPU** | Supports the full model list below, with FP16, FP32, or BF16 precision |
| **Qualcomm NPU** | Runs FP16 models through QNN / HTP; the packaged runtime covers HTP v69, v73, v75, v79, and v81 |
| **MediaTek NPU** | Runs FP16 models through LiteRT / NeuroPilot on ARM64 MediaTek devices with Android 12 or newer |

NPU models are prepared on the device on first use. Settings show preparation progress and offer a retry after failure. Prepared models persist in app-private storage across app restarts; clearing the enhanced-image cache does not remove them.

NPU mode offers compatible models and scales only. INT8 / W8A16 models are no longer packaged. Selecting an NPU backend does not automatically switch to CPU or Vulkan on failure; select Vulkan explicitly to use the GPU.

The MediaTek path has not yet been tested on MediaTek hardware; availability depends on the device and its system drivers. Qualcomm architecture coverage also does not imply that every device, firmware, and model combination has been verified.

### Enhancement models

The following table describes Vulkan support. NPU support is listed separately below.

| Model | Scale | Author / project |
| :--- | :---: | :--- |
| Real-CUGAN SE | 2x / 3x / 4x | [Bilibili AI Lab](https://github.com/bilibili/ailab/tree/main/Real-CUGAN) |
| Real-CUGAN Pro | 2x / 3x | [Bilibili AI Lab](https://github.com/bilibili/ailab/tree/main/Real-CUGAN) |
| Real-CUGAN Nose | 2x | [Bilibili AI Lab](https://github.com/bilibili/ailab/tree/main/Real-CUGAN) |
| Real-ESRGAN animevideov3 | 2x / 3x / 4x | [Xintao Wang et al.](https://github.com/xinntao/Real-ESRGAN) |
| Real-ESRGAN general-x4v3 Photo | 2x | [Xintao Wang et al.](https://github.com/xinntao/Real-ESRGAN) |
| Waifu2x / Waifu2x Fast | 2x | [nagadomi](https://github.com/nagadomi/waifu2x) |
| W2xEX Universal Fast / Omni Mini V2 / Photo Small | 2x | AaronFeng753 · [Model files](https://huggingface.co/randomblock1/W2xEX-ESRGAN) |
| AnimeJaNai v2 UltraCompact | 2x | the-database · [OpenModelDB](https://openmodeldb.info/models/2x-AnimeJaNai-v2-UltraCompact) |
| sudo UltraCompact | 2x | sudo rm -rf / --no-preserve-root#8353 · [OpenModelDB](https://openmodeldb.info/models/2x-sudo-UltraCompact) |
| SPAN NomosUni Photo | 2x | Philip Hofmann · [OpenModelDB](https://openmodeldb.info/models/2x-NomosUni-span-multijpg-ldl) |

### NPU models

Both Qualcomm and MediaTek backends use these 15 FP16 model variants:

| Model | Scale | Variants |
| :--- | :---: | :--- |
| Real-ESRGAN animevideov3 | 2x | Standard |
| Real-ESRGAN general-x4v3 Photo | 2x | Standard |
| Real-CUGAN SE | 2x | No denoise, denoise 1 / 2 / 3, conservative |
| Real-CUGAN Pro | 2x / 3x | No denoise, denoise 3, conservative |
| W2xEX Photo Small | 2x | Standard |
| SPAN NomosUni Photo | 2x | Standard |

## Reader features

Retains Mihon's library, categories, tracking, backups, local reading, and webtoon support, with additional book page turns, image enhancement, ink filters, and Depth Anything V3 spatial images. Spatial images use a separate depth model and Qualcomm processing pipeline.

## Building locally

Use the Gradle Wrapper and configure your Android SDK in the ignored `local.properties` file. By default, ncnn is loaded from `third_party/ncnn-20260113-android-vulkan` in the repository.

```sh
# Release build
./gradlew assembleRelease

# GitHub distribution settings, with optional telemetry disabled
./gradlew assembleRelease -Pdist=github -Pinclude-telemetry=false
```

NPU builds require additional model preparation. Model binaries and local SDK configuration are not committed to Git:

- **Qualcomm:** configure the matching QAIRT SDK and 15 FP16 DLCs. Contexts are compiled and cached on the device. See the [QNN model pipeline](tools/qnn/README.md).
- **MediaTek:** export and configure 15 FP16 TFLite models. See [MediaTek build and compatibility details](docs/mediatek-npu.md).
- **Comparison tools:** [NPU context preparation and inference benchmark](tools/npu-context-benchmark/README.md).

## Credits and license

Thanks to Mihon, Tachiyomi, ncnn, the image enhancement model authors, and all contributors. Models and third-party components retain their respective licenses and attribution requirements.

This project is distributed under the [Apache License 2.0](LICENSE), retaining the upstream copyright notices:

```text
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
```

The project hosts no manga content and is not affiliated with third-party content providers.
