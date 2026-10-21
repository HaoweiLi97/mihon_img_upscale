# Mihon_img_upscale

基于 [Mihon](https://github.com/mihonapp/mihon) 的漫画阅读器，集成 AI 图像增强、Vulkan GPU 加速、Qualcomm / MediaTek NPU 处理和书本翻页效果。

[下载最新版](https://github.com/HaoweiLi97/mihon_img_upscale/releases/latest) · [更新记录](CHANGELOG.md) · [反馈问题](https://github.com/HaoweiLi97/mihon_img_upscale/issues)

<p align="center">
  <img width="350" alt="Mihon AI 图像增强预览" src="https://github.com/user-attachments/assets/c5d68b57-d93d-4a6e-b426-b8624ebcc5e8">
</p>

## 安装

需要 **Android 8.0 或更高版本**。从本仓库的 [Releases](https://github.com/HaoweiLi97/mihon_img_upscale/releases) 下载安装包：

| 安装包 | 适用设备 |
| :--- | :--- |
| `app-arm64-v8a-release.apk` | 大多数现代手机和平板；使用 NPU 时请选择此版本 |
| `app-armeabi-v7a-release.apk` | 旧款 32 位 ARM 设备 |
| `app-x86-release.apk` | 32 位 x86 设备或模拟器 |
| `app-x86_64-release.apk` | 64 位 x86 设备或模拟器 |
| `app-universal-release.apk` | 包含所有架构，不确定时可选，体积较大 |

本仓库的正式版沿用同一发布签名，可以覆盖升级。原版 Mihon 或其他分支使用不同签名，不能直接覆盖安装。

## 图像增强

在阅读器设置中开启「图像增强」，选择处理后端、模型和放大倍数。可以分别设置处理前的分辨率上限、跳过增强的分辨率上限，以及预加载范围。

- 优先处理当前可见页面，再处理附近的预加载页面。
- 增强结果保存到本地缓存，避免重复计算；墨水滤镜配置也会区分缓存。
- 显示排队、增强进度和处理完成状态。
- 保留原图，在 NPU 准备或推理失败时继续显示原图。

### 处理后端

| 后端 | 说明 |
| :--- | :--- |
| **Vulkan GPU** | 支持下表中的完整模型列表，精度可选 FP16、FP32 或 BF16 |
| **Qualcomm NPU** | 通过 QNN / HTP 运行 FP16 模型，当前打包运行时覆盖 HTP v69、v73、v75、v79、v81 |
| **MediaTek NPU** | Android 12+ 的 ARM64 联发科设备，通过 LiteRT / NeuroPilot 运行 FP16 模型 |

NPU 模型首次使用时在设备上准备，设置中会显示准备状态，并提供失败重试。准备后的模型缓存在应用私有目录，重启应用后可继续复用；清理增强图片缓存不会删除这些模型缓存。

NPU 选项只提供兼容的模型和倍数，当前不打包 INT8 / W8A16 模型。选择 NPU 后不会自动切换到 CPU 或 Vulkan；需要 GPU 处理时请手动选择 Vulkan。

联发科 NPU 路径尚未在对应硬件上完成实测，实际可用性取决于设备和系统驱动。Qualcomm 的架构支持也不代表所有设备、固件和模型组合均已验证。

### 增强模型

下表为 Vulkan 后端的模型支持范围；NPU 可用范围见下一节。

| 模型 | 倍数 | 作者 / 项目 |
| :--- | :---: | :--- |
| Real-CUGAN SE | 2x / 3x / 4x | [Bilibili AI Lab](https://github.com/bilibili/ailab/tree/main/Real-CUGAN) |
| Real-CUGAN Pro | 2x / 3x | [Bilibili AI Lab](https://github.com/bilibili/ailab/tree/main/Real-CUGAN) |
| Real-CUGAN Nose | 2x | [Bilibili AI Lab](https://github.com/bilibili/ailab/tree/main/Real-CUGAN) |
| Real-ESRGAN animevideov3 | 2x / 3x / 4x | [Xintao Wang 等](https://github.com/xinntao/Real-ESRGAN) |
| Real-ESRGAN general-x4v3 Photo | 2x | [Xintao Wang 等](https://github.com/xinntao/Real-ESRGAN) |
| Waifu2x / Waifu2x Fast | 2x | [nagadomi](https://github.com/nagadomi/waifu2x) |
| W2xEX Universal Fast / Omni Mini V2 / Photo Small | 2x | AaronFeng753 · [模型文件](https://huggingface.co/randomblock1/W2xEX-ESRGAN) |
| AnimeJaNai v2 UltraCompact | 2x | the-database · [OpenModelDB](https://openmodeldb.info/models/2x-AnimeJaNai-v2-UltraCompact) |
| sudo UltraCompact | 2x | sudo rm -rf / --no-preserve-root#8353 · [OpenModelDB](https://openmodeldb.info/models/2x-sudo-UltraCompact) |
| SPAN NomosUni Photo | 2x | Philip Hofmann · [OpenModelDB](https://openmodeldb.info/models/2x-NomosUni-span-multijpg-ldl) |

### NPU 模型范围

Qualcomm 和 MediaTek 后端均使用以下 15 个 FP16 模型变体：

| 模型 | 倍数 | 变体 |
| :--- | :---: | :--- |
| Real-ESRGAN animevideov3 | 2x | 标准 |
| Real-ESRGAN general-x4v3 Photo | 2x | 标准 |
| Real-CUGAN SE | 2x | 无降噪、降噪 1 / 2 / 3、保守模式 |
| Real-CUGAN Pro | 2x / 3x | 无降噪、降噪 3、保守模式 |
| W2xEX Photo Small | 2x | 标准 |
| SPAN NomosUni Photo | 2x | 标准 |

## 阅读器功能

保留 Mihon 的书库、分类、追踪、备份、本地阅读和条漫阅读等功能，并提供书本翻页、图像增强、墨水滤镜和基于 Depth Anything V3 的空间图像功能。空间图像使用独立的深度模型与 Qualcomm 处理流程。

## 本地构建

项目使用 Gradle Wrapper。Android SDK 路径通过忽略提交的 `local.properties` 配置；ncnn 默认使用仓库中的 `third_party/ncnn-20260113-android-vulkan`。

```sh
# 正式版
./gradlew assembleRelease

# GitHub 发布配置，可按需关闭遥测
./gradlew assembleRelease -Pdist=github -Pinclude-telemetry=false
```

NPU 模型需要额外准备，模型二进制和本机 SDK 配置不提交到 Git：

- **Qualcomm**：配置匹配的 QAIRT SDK 和 15 个 FP16 DLC；在设备上生成并缓存 context，详见 [QNN 模型流程](tools/qnn/README.md)。
- **MediaTek**：导出并配置 15 个 FP16 TFLite 模型，详见 [联发科 NPU 构建与兼容性说明](docs/mediatek-npu.md)。
- **对比工具**：[NPU context 准备与推理基准](tools/npu-context-benchmark/README.md)。

## 致谢与许可证

感谢 Mihon、Tachiyomi、ncnn，以及各图像增强模型的作者和贡献者。模型及第三方组件遵循各自的许可证和署名要求。

本项目基于 [Apache License 2.0](LICENSE) 发布，保留上游版权声明：

```text
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
```

项目不提供漫画内容，与第三方内容提供者没有关联。
