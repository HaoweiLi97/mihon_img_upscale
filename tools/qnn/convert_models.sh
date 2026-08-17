#!/bin/sh

set -eu

: "${ONNX_DIR:?Set ONNX_DIR to the directory containing exported ONNX models}"
: "${OUTPUT_DIR:?Set OUTPUT_DIR to the QNN converter output directory}"

QNN_SDK_ROOT=${QNN_SDK_ROOT:-/qnn}
CONVERTER="$QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-onnx-converter"

mkdir -p "$OUTPUT_DIR"

for model in "$ONNX_DIR"/*.onnx; do
    name=$(basename "$model" .onnx)
    "$CONVERTER" \
        --input_network "$model" \
        --input_layout input NCHW \
        --input_encoding input rgb rgb \
        --float_bitwidth 16 \
        --exclude_named_tensors \
        --output_path "$OUTPUT_DIR/$name.cpp"
done
