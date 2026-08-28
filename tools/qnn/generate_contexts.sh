#!/bin/sh

set -eu

: "${MODEL_LIB_DIR:?Set MODEL_LIB_DIR to the x86_64 QNN model library directory}"
: "${OUTPUT_DIR:?Set OUTPUT_DIR to the context binary output directory}"

QNN_SDK_ROOT=${QNN_SDK_ROOT:-/qnn}
QNN_HTP_ARCHS=${QNN_HTP_ARCHS:-${QNN_HTP_ARCH:-69}}
INT8_MODEL_LIB_DIR=${INT8_MODEL_LIB_DIR:-$MODEL_LIB_DIR}
GENERATOR="$QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-context-binary-generator"
BACKEND="$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so"

mkdir -p "$OUTPUT_DIR"

models=${QNN_MODELS:-"\
    realesrgan-animevideov3-x2 \
    realesrgan-animevideov3-x2-int8 \
    realesrgan-general-x4v3-x2 \
    realesrgan-general-x4v3-x2-int8 \
    realcugan-se-x2-no-denoise \
    realcugan-se-x2-denoise1x \
    realcugan-se-x2-denoise2x \
    realcugan-se-x2-denoise3x \
    realcugan-se-x2-conservative \
    realcugan-se-x2-no-denoise-int8 \
    realcugan-se-x2-denoise1x-int8 \
    realcugan-se-x2-denoise2x-int8 \
    realcugan-se-x2-denoise3x-int8 \
    realcugan-se-x2-conservative-int8 \
    realcugan-pro-x2-no-denoise \
    realcugan-pro-x2-denoise3x \
    realcugan-pro-x2-conservative \
    realcugan-pro-x2-no-denoise-int8 \
    realcugan-pro-x2-denoise3x-int8 \
    realcugan-pro-x2-conservative-int8 \
    realcugan-pro-x3-no-denoise \
    realcugan-pro-x3-denoise3x \
    realcugan-pro-x3-conservative \
    realcugan-pro-x3-no-denoise-int8 \
    realcugan-pro-x3-denoise3x-int8 \
    realcugan-pro-x3-conservative-int8 \
    span-nomosuni-x2 \
    span-nomosuni-x2-int8 \
    w2xex-photo-small-x2 \
    w2xex-photo-small-x2-int8"}

for htp_arch in $(printf '%s' "$QNN_HTP_ARCHS" | tr ',' ' '); do
    htp_arch=${htp_arch#v}
    case "$htp_arch" in
        69) generator_soc_model=42 ;;
        73) generator_soc_model=43 ;;
        75) generator_soc_model=57 ;;
        79) generator_soc_model=69 ;;
        81) generator_soc_model=87 ;;
        *)
            echo "Unsupported QNN HTP architecture: v$htp_arch" >&2
            exit 1
            ;;
    esac

    for model in $models; do
        model_dir=$MODEL_LIB_DIR
        case "$model" in
            *-int8) model_dir=$INT8_MODEL_LIB_DIR ;;
        esac
        model_path="$model_dir/lib$model.so"
        if [ ! -f "$model_path" ]; then
            echo "Missing QNN model library: $model_path" >&2
            exit 1
        fi
        "$GENERATOR" \
            --model="$model_path" \
            --backend="$BACKEND" \
            --binary_file="$model.v$htp_arch" \
            --output_dir="$OUTPUT_DIR" \
            --soc_model="$generator_soc_model" \
            --log_level=error
    done
done
