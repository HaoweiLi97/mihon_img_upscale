#!/bin/sh

set -eu

: "${ONNX_DIR:?Set ONNX_DIR to the directory containing exported ONNX models}"
: "${CALIBRATION_INPUT_LIST:?Set CALIBRATION_INPUT_LIST to the QNN calibration input list}"
: "${OUTPUT_DIR:?Set OUTPUT_DIR to the QNN converter output directory}"

QNN_SDK_ROOT=${QNN_SDK_ROOT:-/qnn}
CONVERTER="$QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-onnx-converter"

mkdir -p "$OUTPUT_DIR"

models="\
    realesrgan-animevideov3-x2 \
    realcugan-se-x2-no-denoise \
    realcugan-se-x2-denoise1x \
    realcugan-se-x2-denoise2x \
    realcugan-se-x2-denoise3x \
    realcugan-se-x2-conservative"

for name in $models; do
    model="$ONNX_DIR/$name.onnx"
    if [ ! -f "$model" ]; then
        echo "Missing ONNX model: $model" >&2
        exit 1
    fi
    "$CONVERTER" \
        --input_network "$model" \
        --input_layout input NCHW \
        --input_encoding input rgb rgb \
        --float_bitwidth 16 \
        --exclude_named_tensors \
        --input_list "$CALIBRATION_INPUT_LIST" \
        --act_bitwidth 8 \
        --weights_bitwidth 8 \
        --bias_bitwidth 32 \
        --use_per_channel_quantization \
        --algorithms cle \
        --output_path "$OUTPUT_DIR/$name-int8.cpp"
done
