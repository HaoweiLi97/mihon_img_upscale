# Qualcomm QNN FP16 model pipeline

The app bundles **unprepared FP16 DLCs**, not precompiled per-SoC/HTP context binaries.
Selecting an NPU model prepares just that model on the device. Subsequent use and app
restarts reuse its serialized context from `noBackupFilesDir/qnn-contexts/`.
Image-cache cleanup does not remove these contexts. Uninstalling or clearing app data does.

Models currently included (15 variants):

- Real-ESRGAN animevideov3 2x and general-x4v3 Photo with 2x output
- Real-CUGAN SE 2x: no-denoise, denoise1x, denoise2x, denoise3x, conservative
- Real-CUGAN Pro 2x and 3x: no-denoise, denoise3x, conservative
- W2xEX Photo Small 2x
- SPAN NomosUni Photo 2x

NPU inference is FP16 only. INT8/W8A16 assets are not packaged; old INT8 preferences
resolve to FP16. Vulkan retains the FP16, FP32 and BF16 choices.

## Build inputs

Use QAIRT SDK **2.49.0.260730**, matching `com.qualcomm.qti:qnn-runtime:2.49.0`.
Configure ignored repository `local.properties`:

```properties
qnn.sdk.dir=/absolute/path/to/qairt/2.49.0.260730
qnn.dlc.dir=/absolute/path/to/generated/dlc-fp16
```

Gradle also accepts `-PqnnSdkDir=...`, `-PqnnDlcDir=...`, `QNN_SDK_ROOT`, and
`QNN_DLC_DIR`. The previous `qnn.context.dir` and `qnn.htp.archs` inputs are unused.
`stageQnnModels` verifies all 15 DLCs and generates a SHA-256 content manifest.
No model binary is committed to Git.

The official runtime AAR supplies HTP, HTP Prepare, System, and matching Stub/Skel
libraries. Gradle additionally stages the SDK's `libQnnModelDlc.so` for DLC composition.
The compiler uses the same shared runtime as the spatial depth pipeline.

## Export and convert

Export upstream model weights to fixed-shape ONNX using `export_models.py`,
`export_w2xex_onnx.py`, and the existing SPAN exporter. ONNX inputs are NCHW RGB
float tensors in [0,1], shape `1,3,256,256`; output is NCHW RGB, 2x or 3x.
Real-CUGAN Pro is reconstructed from official NCNN `models-pro` files because
equivalent upstream PyTorch weights are not published. See exporter `--help`.

In the QAIRT Linux Python environment:

```sh
export QNN_SDK_ROOT=/absolute/path/to/qairt/2.49.0.260730
export PYTHONPATH="$QNN_SDK_ROOT/lib/python:$PYTHONPATH"
export LD_LIBRARY_PATH="$QNN_SDK_ROOT/lib/x86_64-linux-clang:$LD_LIBRARY_PATH"
ONNX_DIR=/absolute/path/to/onnx OUTPUT_DIR=/absolute/path/to/dlc-fp16 \
  sh tools/qnn/convert_dlc_models.sh
```

The script converts all 15 models with `qairt-converter --float_bitwidth 16`,
and explicitly converts input/output layouts from NCHW to NHWC. It does not
quantize, calibrate, or generate an offline context. `QNN_MODELS` can select a
subset for conversion, but a normal app build requires the full set.

Alternatively, the existing Docker image includes `convert-qnn-dlc-models`:

```sh
docker build --platform linux/amd64 -t mihon-qnn-tools:2.49.0 tools/qnn
docker run --rm --platform linux/amd64 \
  -v "$QNN_SDK_ROOT:/qnn:ro" -v "$ONNX_DIR:/models:ro" -v "$DLC_DIR:/output" \
  -e ONNX_DIR=/models -e OUTPUT_DIR=/output \
  mihon-qnn-tools:2.49.0 convert-qnn-dlc-models
```

Older conversion/quantization and offline-generation scripts remain developer utilities
for comparisons. The app no longer consumes their context or INT8 outputs.

## Persistence and recovery

Cache identity includes model contents, QNN runtime and context-format versions,
detected HTP architecture, Android build fingerprint, and ABI. Compatibility changes
create a new identity. Contexts are committed via temporary files; a saved SHA-256
detects interrupted or corrupted writes before reuse. Obsolete versions of a model
are removed only after its replacement succeeds.

UI preparation and first inference share one preparation lock, preventing duplicate
compilations. The UI shows preparing/ready/failed state and offers retry. Failure skips enhancement and keeps the original image. Vulkan is used only when
explicitly selected. The compiled model is loaded
to validate it before being marked ready.

## Supported hardware and validation

Architecture detection uses QNN device capabilities, not a list of flagship SoC names.
The packaged FP16 runtime set currently supports HTP **v69, v73, v75, v79, v81**,
subject to drivers, memory and operator support. On-device compilation removes
per-target binary distribution; it does not add unsupported hardware to QNN.

`QualcommHtpDeviceTest` covers packaging, persistence, corruption recovery, legacy
INT8 normalization, and compilation plus production inference for all 15 FP16 variants.
The independent prototype and real-image reference comparison are in
`tools/npu-context-benchmark/`.

MediaTek requires a separate backend; see [MediaTek investigation](../../docs/mediatek-npu.md).
