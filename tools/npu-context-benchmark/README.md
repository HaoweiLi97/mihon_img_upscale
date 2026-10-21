# Android NPU context benchmark

Independent Android app (`dev.mihon.npubench`) for **Real-CUGAN Pro 2x denoise3x FP16**.
It does not modify or replace Mihon. No network connection is required.

The first button deletes this test app's saved context and compiles the bundled, unprepared
DLC on the phone using QAIRT 2.49. It measures runtime initialization, DLC composition +
graph finalization + context serialization, saved-context loading, and one synthetic tile
inference separately. Model extraction is excluded from native compile timing.
The second button loads the saved context without compiling again.
The UI also shows elapsed wall time and process peak RSS (since process launch).
Results are saved to the app's private `files/last-result.txt`; the copy button copies the
currently displayed log. A marker records the last stage if the process is killed.

The inference is only a smoke check of output shape and finite values, not an image quality
comparison or steady-state inference benchmark. It uses the shared production DLC runtime
with its default graph optimization settings; it does not tune optimization levels.

Build from the repository root:

```powershell
./gradlew.bat -p tools/npu-context-benchmark assembleDebug
```

Prerequisites: Android SDK/NDK from the repository `local.properties`, QAIRT SDK
`qnn.sdk.dir` (or `-PqnnSdkDir=...`), and the generated, unprepared DLC at
`src/main/assets/realcugan-pro-x2-denoise3x-fp16.dlc`. Runtime libraries are staged from
the SDK at build time, including `libQnnHtpPrepare.so` for on-device compilation.
Model binaries and generated outputs are ignored by Git.

Copy the root `local.properties`'s `sdk.dir` setting into this directory's own
ignored `local.properties` before building, or set `ANDROID_HOME`.
Generate the DLC in a Linux QAIRT Python environment (no quantization or offline preparation):

```sh
python "$QNN_SDK_ROOT/bin/x86_64-linux-clang/qairt-converter" \
  --input_network "$ONNX_DIR/realcugan-pro-x2-denoise3x.onnx" \
  --source_model_input_layout input NCHW --desired_input_layout input NHWC \
  --source_model_output_layout output NCHW --desired_output_layout output NHWC \
  --float_bitwidth 16 \
  --output_path tools/npu-context-benchmark/src/main/assets/realcugan-pro-x2-denoise3x-fp16.dlc
```

Install and open:

```powershell
adb install -r tools/npu-context-benchmark/build/outputs/apk/debug/NpuContextBenchmark-debug.apk
adb shell am start -n dev.mihon.npubench/.MainActivity
```

Press **开始 / 重新编译 Context** to run. Keep the page open until it finishes.
For repeated trials each cold run removes the serialized context, but OS/library caches
may remain warm. Stop/reopen the app or reboot when comparing process/device cold starts.

## Validate real-image outputs

After compiling once in the app, `validate_context.py` extracts that exact saved binary
without replacing it. It executes seven real tiles on the device, compares against the
original FP32 ONNX and existing offline FP16 v69 context, saves numerical metrics and
an image comparison sheet, and checks the app context's hash is unchanged.
This validation is currently configured for the attached SM8475 / HTP v69 device.
Python dependencies: `numpy`, `onnxruntime`, `Pillow`.

```sh
python tools/npu-context-benchmark/validate_context.py \
  --sdk "$QNN_SDK_ROOT" --data "$QNN_DATA_ROOT" \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial HA1V090G
```

`QNN_DATA_ROOT` is the existing model pipeline data directory containing `onnx/`,
`active-contexts/`, `validation-manga/` and `validation-photo/`. Under WSL an
`adb.exe` path and `/mnt/c/...` data paths are supported. See RESULTS.md for measured results.
