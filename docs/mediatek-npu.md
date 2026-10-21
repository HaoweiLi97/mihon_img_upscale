# MediaTek NPU backend

The reader now offers MediaTek NPU on Android 12+ arm64 MediaTek devices. It uses
the official LiteRT 2.3.0 runtime and NeuroPilot compiler/dispatch Maven artifacts.
The existing 15 NPU model variants are exported as fixed-shape TFLite models with
FP16 weights and FP32 NHWC RGB input/output. Qualcomm keeps its separate QNN DLC
and serialized-context path. No INT8 variants are exposed or packaged.

Selecting a model prepares it on an IO worker. The model SHA-256, runtime/cache
version, Android build fingerprint and ABI identify an app-private directory in
`noBackupFilesDir/mediatek-npu`. The verified TFLite model and LiteRT compiler
cache remain there across launches. LiteRT also invalidates its compiled artifacts
when its compiler or compilation options change. Image-cache eviction does not
remove prepared models. Concurrent reader/UI preparation is serialized, and a
failed preparation requires an explicit retry during that process.

Preparation uses a temporary native runtime. Reader initialization loads the chosen
model from the same persistent compiler cache. Native inference shares the reader's
existing output, monochrome protection and alpha handling, with fixed 256-pixel
input tiles and each model's established overlap. Device libraries are declared
optional in the manifest, so their absence does not prevent installation.

Both NPU backends follow the same failure policy: **skip enhancement and retain the
original image**. The stored backend choice is preserved. MediaTek requests NPU
only and rejects a compiled graph unless `LiteRtCompiledModelIsFullyAccelerated`
confirms complete acceleration. There is no automatic CPU or Vulkan fallback.
Vulkan runs only when explicitly selected. Failed native inference returns null,
so the original cannot be saved as a successful enhanced result. Input prescaling
uses a temporary bitmap and preserves the original for display on failure.

## Export and build

Use Python 3.11 on Linux/WSL with `tools/mediatek/requirements.txt`:

```sh
python -m venv .venv
.venv/bin/pip install -r tools/mediatek/requirements.txt
.venv/bin/python tools/mediatek/export_models.py \
  --data /path/to/qnn-model-data \
  --output /path/to/tflite-fp16
```

`--data` contains the existing `onnx/` models and `validation-manga/input-000.raw`
and `validation-photo/input-000.raw` FP32 NCHW reference inputs. The translator
explicitly handles this model set's operators and layouts; it is not a general
ONNX importer. Unsupported operations fail conversion. Every export compares CPU
LiteRT output against FP32 ONNX, then records hashes and numerical metrics in
`validation.json`. Configure `mediatek.tflite.dir` in local.properties,
`-PmediatekTfliteDir=...`, or `MEDIATEK_TFLITE_DIR` when building. Gradle stages
all 15 models and their checksum manifest under `assets/litert-models`.

## Validation limits

All 15 exports passed desktop CPU numerical checks against the source ONNX models.
The connected device is Qualcomm SM8475; no MediaTek NPU device is available.
MediaTek NPU compilation, full delegation, latency and actual image quality remain
unverified. Android instrumentation tests include explicit debug-only CPU C ABI/tile
validation and unavailable-NPU/no-Vulkan regression cases; these new tests have not
been run. Release native code rejects the CPU validation switch.

Google's [MediaTek guide](https://developers.google.cn/edge/litert/next/mediatek)
lists Dimensity 7300, 8300, 9000, 9200, 9300, 9400 and 9500. This is a documented
platform list, not a compatibility claim for every model, firmware or MediaTek SoC.
The [NPU guide](https://developers.google.cn/edge/litert/next/npu) documents on-device
compilation, vendor runtime packaging and `CompilerCacheDir` persistence.
