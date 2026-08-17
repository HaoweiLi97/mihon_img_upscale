#!/bin/sh

set -eu

: "${CONVERTED_DIR:?Set CONVERTED_DIR to the QNN converter output directory}"
: "${OUTPUT_DIR:?Set OUTPUT_DIR to the model library output directory}"

QNN_SDK_ROOT=${QNN_SDK_ROOT:-/qnn}
GENERATOR="$QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-model-lib-generator"

mkdir -p "$OUTPUT_DIR"

for model in "$CONVERTED_DIR"/*.cpp; do
    name=$(basename "$model" .cpp)
    "$GENERATOR" \
        --cpp "$model" \
        --bin "$CONVERTED_DIR/$name.bin" \
        --lib_targets x86_64-linux-clang \
        --lib_name "$name" \
        --output_dir "$OUTPUT_DIR"
done
