# Qualcomm QNN model pipeline

The app bundles unprepared FP16 DLCs and one prequantized INT8 **W8A8** DLC.
The phone compiles the selected DLC for its own HTP, then persists the context
outside the image cache. No calibration samples, decryption key, calibration
runtime or dataset download is included in the app.

## Precision choices

| Model | FP16 | INT8 W8A8 |
| --- | --- | --- |
| Real-ESRGAN Anime (animevideov3 2x) | Yes | Yes |
| Real-ESRGAN Photo (general-x4v3, 2x output) | Yes | No |
| Real-CUGAN SE 2x, all five variants | Yes | No |
| Real-CUGAN Pro 2x/3x, all six variants | Yes | No |
| SPAN NomosUni Photo 2x | Yes | No |
| W2xEX Photo Small 2x | Yes | No |

The existing quantization pipeline uses wider 16-bit activations for the fourteen
FP16-only variants. These remain conservatively excluded from W8A8 due to precision
risk; their models remain available in FP16. Only Anime exposes the INT8 option.
Changing the Real-ESRGAN style to Photo removes INT8. Legacy local W8A16 preferences
migrate to INT8 for Anime and FP16 for the other models. Vulkan and MediaTek precision
choices are unchanged.

FP16 capability is probed on the actual QNN backend. Unsupported FP16 is greyed out.
An integer-only device can select Anime with INT8; FP16-only models report that
another model or Vulkan is required. Transient probe errors remain unknown rather
than disabling FP16. There is no chipset-name blocklist.

## Build inputs

Use QAIRT **2.49.0.260730**, matching `com.qualcomm.qti:qnn-runtime:2.49.0`.
Configure ignored `local.properties`:

```properties
qnn.sdk.dir=/absolute/path/to/qairt/2.49.0.260730
qnn.dlc.dir=/absolute/path/to/generated/dlc-fp16
qnn.w8a8.dlc.dir=/absolute/path/to/generated/dlc-w8a8
```

Gradle also accepts `-PqnnSdkDir`, `-PqnnDlcDir`, `-PqnnW8a8DlcDir`, or the
`QNN_SDK_ROOT`, `QNN_DLC_DIR`, `QNN_W8A8_DLC_DIR` environment variables.
The W8A8 directory defaults to `../dlc-w8a8` next to the FP16 directory.
`stageQnnModels` requires all 15 FP16 DLCs and
`realesrgan-animevideov3-x2-w8a8.dlc`, and creates their SHA-256 manifest.
A missing INT8 DLC fails the build instead of substituting a floating-point model.
Model binaries and developer calibration inputs remain outside Git.

The runtime AAR provides HTP, HTP Prepare, System and Stub/Skel libraries. Gradle
also packages `libQnnModelDlc.so` for DLC composition. `libQnnCpu.so` is no longer
packaged for calibration.

## Developer conversion

Export fixed-shape ONNX using the existing exporters, then convert the FP16 DLCs:

```sh
export QNN_SDK_ROOT=/path/to/qairt/2.49.0.260730
export PYTHONPATH="$QNN_SDK_ROOT/lib/python:$PYTHONPATH"
export LD_LIBRARY_PATH="$QNN_SDK_ROOT/lib/x86_64-linux-clang:$LD_LIBRARY_PATH"
ONNX_DIR=/path/to/onnx OUTPUT_DIR=/path/to/dlc-fp16 \
  sh tools/qnn/convert_dlc_models.sh
```

Prepare the W8A8 DLC on the developer machine using existing normalized float32
RGB/NHWC 256x256 calibration crops:

```sh
FP16_DLC_DIR=/path/to/dlc-fp16 OUTPUT_DIR=/path/to/dlc-w8a8 \
MANGA_CALIBRATION_INPUT_LIST=/private/path/to/input-list.txt \
  sh tools/qnn/convert_w8a8_dlc_models.sh
```

This uses 8-bit weights and activations, 32-bit biases, per-channel convolution
weight quantization and cross-layer equalization. It does not enable float
fallback or preserve floating-point I/O. `QNN_MODELS` cannot enable risky models:
the conversion script rejects names outside the same conservative allowlist.
The DLC inspector then rejects floating-point or wider activation/weight tensors.
Older `convert_int8_models.sh` outputs may use W8A16 and must not be renamed W8A8.
The Docker tool image provides `convert-qnn-w8a8-dlc-models` as well.
Calibration samples are developer-only build inputs and are never downloaded by
this version of the application. Historical calibration releases are unused.

## Persistence and validation

Context identity includes model contents, precision, QNN/cache format versions,
HTP architecture, Android build fingerprint and ABI. INT8 and FP16 have separate
preparation states, model and image caches. Contexts are written through temporary
files and verified against a saved SHA-256. Previous W8A16 contexts are not reused.
Failures keep the original image; Vulkan is only used when explicitly selected.

Runtime hardware detection supports packaged HTP v69, v73, v75, v79 and v81,
subject to device drivers and QNN operator support. On-device compilation does
not imply that every chipset can execute every model.

`QnnPrecisionPolicyTest` checks all 14 exclusions, Anime vs Photo, retired
preferences and image cache separation. `QualcommHtpDeviceTest` checks final APK
packaging and provides physical-device FP16 and W8A8 compile/inference tests.
CPU simulation and host HTP compilation cannot replace physical NPU validation.

The initial Anime W8A8 DLC passed QNN CPU simulation on eight held-out manga crops
against the original FP32 ONNX (PSNR 34.21–38.00 dB, MAE at most 0.01312). Host
compilation passed for HTP v69/v73/v75/v79/v81, including the integer-only SM8735
target. These are developer checks, not physical NPU inference results.

MediaTek remains separate; see [MediaTek investigation](../../docs/mediatek-npu.md).
