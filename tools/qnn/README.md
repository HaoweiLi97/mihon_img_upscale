# Qualcomm QNN model pipeline

Supported targets are Snapdragon 8+ Gen 1 through Snapdragon 8 Elite Gen 5. Models use a
fixed 256x256 RGB NCHW input tile and produce a 512x512 RGB NCHW output tile.

| Product | SoC | HTP architecture |
| --- | --- | --- |
| Snapdragon 8+ Gen 1 | SM8475 | v69 |
| Snapdragon 8 Gen 2 | SM8550 | v73 |
| Snapdragon 8 Gen 3 | SM8650 | v75 |
| Snapdragon 8 Elite | SM8750 | v79 |
| Snapdragon 8 Elite Gen 5 | SM8850 | v81 |

Supported models:

- Real-ESRGAN animevideov3 2x: FP16 and INT8
- Real-ESRGAN general-x4v3 Photo 2x output: FP16 and W8A16 mixed precision
- Real-CUGAN SE 2x: FP16 and INT8 for no-denoise, denoise1x, denoise2x,
  denoise3x, and conservative

Local prerequisites are configured through ignored `local.properties` values:

```properties
qnn.sdk.dir=/absolute/path/to/qairt/version
qnn.htp.archs=69,73,75,79,81
qnn.context.dir=/absolute/path/to/generated/contexts
```

Gradle downloads Qualcomm's official `com.qualcomm.qti:qnn-runtime:2.49.0` Android runtime.
It packages the matching HTP Stub and Skel libraries for v69, v73, v75, v79, and v81, so
these runtime files do not need to be copied from the local SDK. The SDK path is still needed
for QNN headers and the model conversion/context generation tools.

Export the upstream PyTorch weights to ONNX with `export_models.py`. The resulting ONNX
files are development inputs and should not be committed. QNN conversion and quantization
scripts will consume these fixed-shape graphs and emit Android context binaries. Build the
amd64 Linux converter environment before running either conversion path:

```sh
docker build --platform linux/amd64 -t mihon-qnn-tools:2.49.0 tools/qnn
```

Convert the exported models to FP16:

```sh
docker run --rm --platform linux/amd64 \
  -v "$QNN_SDK_ROOT:/qnn:ro" \
  -v "$ONNX_DIR:/models:ro" \
  -v "$CONVERTED_DIR:/output" \
  -e ONNX_DIR=/models \
  -e OUTPUT_DIR=/output \
  mihon-qnn-tools:2.49.0 \
  convert-qnn-models
```

Create calibration inputs and convert the supported x2 models to INT8. The calibration input
list paths must match the path mounted inside the converter container.

```sh
python tools/qnn/prepare_calibration.py \
  --image-dir "$CALIBRATION_IMAGE_DIR" \
  --output-dir "$CALIBRATION_DIR" \
  --tile-size 256 \
  --samples 64 \
  --input-list-prefix /calibration

docker run --rm --platform linux/amd64 \
  -v "$QNN_SDK_ROOT:/qnn:ro" \
  -v "$ONNX_DIR:/models:ro" \
  -v "$CALIBRATION_DIR:/calibration:ro" \
  -v "$INT8_CONVERTED_DIR:/output" \
  -e ONNX_DIR=/models \
  -e CALIBRATION_INPUT_LIST=/calibration/input-list.txt \
  -e OUTPUT_DIR=/output \
  mihon-qnn-tools:2.49.0 \
  convert-qnn-int8-models
```

Compile the converter output into model libraries used for HTP context generation:

```sh
docker run --rm --platform linux/amd64 \
  -v "$QNN_SDK_ROOT:/qnn:ro" \
  -v "$CONVERTED_DIR:/converted:ro" \
  -v "$MODEL_LIB_DIR:/output" \
  -e CONVERTED_DIR=/converted \
  -e OUTPUT_DIR=/output \
  mihon-qnn-tools:2.49.0 \
  compile-qnn-model-libs

docker run --rm --platform linux/amd64 \
  -v "$QNN_SDK_ROOT:/qnn:ro" \
  -v "$INT8_CONVERTED_DIR:/converted:ro" \
  -v "$INT8_MODEL_LIB_DIR:/output" \
  -e CONVERTED_DIR=/converted \
  -e OUTPUT_DIR=/output \
  mihon-qnn-tools:2.49.0 \
  compile-qnn-model-libs
```

The generated libraries intentionally keep the converter's default `QnnModel` symbol
prefix. QAIRT 2.49 still displays `--model_prefix` in the context generator help, but the
option is no longer applied to model libraries. Using a filename-derived prefix causes the
generator to fail while looking up `QnnModel_composeGraphs`.

Generate offline HTP contexts for all supported Snapdragon generations. QAIRT's model-library
workflow must pass each target through `--soc_model`; `--htp_socs` only selects targets for the
DLC offline-cache workflow and otherwise leaves model-library contexts on the default HTP
architecture. The generation script maps the supported SoC names to their QAIRT SoC model IDs
and invokes the generator once per model and target.

```sh
docker run --rm --platform linux/amd64 \
  -v "$QNN_SDK_ROOT:/qnn:ro" \
  -v "$MODEL_LIB_DIR:/models:ro" \
  -v "$INT8_MODEL_LIB_DIR:/models-int8:ro" \
  -v "$CONTEXT_DIR:/output" \
  -e MODEL_LIB_DIR=/models \
  -e INT8_MODEL_LIB_DIR=/models-int8 \
  -e OUTPUT_DIR=/output \
  -e QNN_HTP_SOCS=sm8475,sm8550,sm8650,sm8750,sm8850 \
  mihon-qnn-tools:2.49.0 \
  generate-qnn-contexts
```
