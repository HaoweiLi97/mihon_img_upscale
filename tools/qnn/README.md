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

- Real-ESRGAN animevideov3 2x
- Real-CUGAN SE 2x: no-denoise, denoise1x, denoise2x, denoise3x, conservative

Local prerequisites are configured through ignored `local.properties` values:

```properties
qnn.sdk.dir=/absolute/path/to/qairt/version
qnn.htp.archs=69,73,75,79,81
```

Export the upstream PyTorch weights to ONNX with `export_models.py`. The resulting ONNX
files are development inputs and should not be committed. QNN conversion and quantization
scripts will consume these fixed-shape graphs and emit Android context binaries.

Build the converter environment and run the QNN conversion inside its amd64 Linux container:

```sh
docker build -t mihon-qnn-tools:2.49.0 tools/qnn
docker run --rm --platform linux/amd64 \
  -v "$QNN_SDK_ROOT:/qnn:ro" \
  -v "$ONNX_DIR:/models:ro" \
  -v "$OUTPUT_DIR:/output" \
  -e ONNX_DIR=/models \
  -e OUTPUT_DIR=/output \
  mihon-qnn-tools:2.49.0 \
  convert-qnn-models
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
```

The generated libraries intentionally keep the converter's default `QnnModel` symbol
prefix. QAIRT 2.49 still displays `--model_prefix` in the context generator help, but the
option is no longer applied to model libraries. Using a filename-derived prefix causes the
generator to fail while looking up `QnnModel_composeGraphs`.

Generate offline HTP contexts for all supported Snapdragon generations. Each SoC must be
generated independently; passing multiple SoCs to one generator invocation produces invalid
placeholder contexts for later targets with QAIRT 2.49's model-library workflow.

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
