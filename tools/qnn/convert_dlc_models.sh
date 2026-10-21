#!/bin/sh
set -eu

: "${ONNX_DIR:?Set ONNX_DIR to the exported ONNX directory}"
: "${OUTPUT_DIR:?Set OUTPUT_DIR to the unprepared FP16 DLC directory}"
QNN_SDK_ROOT=${QNN_SDK_ROOT:-/qnn}
CONVERTER="$QNN_SDK_ROOT/bin/x86_64-linux-clang/qairt-converter"
models=${QNN_MODELS:-"realesrgan-animevideov3-x2 realesrgan-general-x4v3-x2
realcugan-se-x2-no-denoise realcugan-se-x2-denoise1x realcugan-se-x2-denoise2x
realcugan-se-x2-denoise3x realcugan-se-x2-conservative
realcugan-pro-x2-no-denoise realcugan-pro-x2-denoise3x realcugan-pro-x2-conservative
realcugan-pro-x3-no-denoise realcugan-pro-x3-denoise3x realcugan-pro-x3-conservative
span-nomosuni-x2 w2xex-photo-small-x2"}
mkdir -p "$OUTPUT_DIR"
for model in $models; do
    echo "Converting FP16 DLC: $model"
    "$CONVERTER" --input_network "$ONNX_DIR/$model.onnx" \
        --source_model_input_layout input NCHW --desired_input_layout input NHWC \
        --source_model_output_layout output NCHW --desired_output_layout output NHWC \
        --float_bitwidth 16 --output_path "$OUTPUT_DIR/$model.dlc"
done
