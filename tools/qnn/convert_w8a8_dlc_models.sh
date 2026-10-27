#!/bin/sh
set -eu

: "${FP16_DLC_DIR:?Set FP16_DLC_DIR to the unprepared FP16 DLC directory}"
: "${OUTPUT_DIR:?Set OUTPUT_DIR to the unprepared W8A8 DLC directory}"
: "${MANGA_CALIBRATION_INPUT_LIST:?Set MANGA_CALIBRATION_INPUT_LIST to the developer calibration list}"
QNN_SDK_ROOT=${QNN_SDK_ROOT:-/qnn}
QUANTIZER="$QNN_SDK_ROOT/bin/x86_64-linux-clang/qairt-quantizer"
# Only Anime has an 8-bit activation path in our existing quantization policy.
# Other models require wider activations and must not be packaged as W8A8.
models=${QNN_MODELS:-realesrgan-animevideov3-x2}
for model in $models; do
    case "$model" in
        realesrgan-animevideov3-x2) ;;
        *) echo "W8A8 is not enabled for $model" >&2; exit 1 ;;
    esac
done
mkdir -p "$OUTPUT_DIR"
for model in $models; do
    "$QUANTIZER" --input_dlc "$FP16_DLC_DIR/$model.dlc" \
        --input_list "$MANGA_CALIBRATION_INPUT_LIST" \
        --act_bitwidth 8 --weights_bitwidth 8 --bias_bitwidth 32 \
        --use_per_channel_quantization --apply_algorithms cle \
        --output_dlc "$OUTPUT_DIR/$model-w8a8.dlc"
    python3 "$(dirname "$0")/verify_w8a8_dlc.py" "$OUTPUT_DIR/$model-w8a8.dlc" --sdk "$QNN_SDK_ROOT"
done
