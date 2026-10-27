#ifndef MIHON_QNN_FP16_SUPPORT_H
#define MIHON_QNN_FP16_SUPPORT_H
#include <QnnInterface.h>
#include <QnnOpDef.h>
#include <QnnOpPackage.h>

namespace qnn_ondevice {
// Unknown (-1), integer-only (0), or floating-point capable (1).
inline int fp16_support(const QnnInterface_t &provider,
                        Qnn_BackendHandle_t backend) {
  auto validate = provider.QNN_INTERFACE_VER_NAME.backendValidateOpConfig;
  if (!validate)
    return -1;
  uint32_t dimensions[] = {1, 4, 4, 4};
  Qnn_Tensor_t input = QNN_TENSOR_INIT;
  input.version = QNN_TENSOR_VERSION_2;
  input.v2 = QNN_TENSOR_V2_INIT;
  input.v2.name = "fp16_probe_input";
  input.v2.type = QNN_TENSOR_TYPE_APP_WRITE;
  input.v2.dataType = QNN_DATATYPE_FLOAT_16;
  input.v2.rank = 4;
  input.v2.dimensions = dimensions;
  input.v2.memType = QNN_TENSORMEMTYPE_RAW;
  Qnn_Tensor_t output = input;
  output.v2.name = "fp16_probe_output";
  output.v2.type = QNN_TENSOR_TYPE_APP_READ;
  Qnn_OpConfig_t op = QNN_OPCONFIG_INIT;
  op.v1.name = "fp16_capability_probe";
  op.v1.packageName = QNN_OP_PACKAGE_NAME_QTI_AISW;
  op.v1.typeName = QNN_OP_RELU;
  op.v1.numOfInputs = 1;
  op.v1.inputTensors = &input;
  op.v1.numOfOutputs = 1;
  op.v1.outputTensors = &output;
  const auto status = validate(backend, op);
  if (status == QNN_SUCCESS)
    return 1;
  if (status != QNN_OP_PACKAGE_ERROR_VALIDATION_FAILURE)
    return -1;
  input.v2.dataType = output.v2.dataType = QNN_DATATYPE_UFIXED_POINT_16;
  input.v2.quantizeParams.encodingDefinition = QNN_DEFINITION_DEFINED;
  input.v2.quantizeParams.quantizationEncoding =
      QNN_QUANTIZATION_ENCODING_SCALE_OFFSET;
  input.v2.quantizeParams.scaleOffsetEncoding = {1.0f / 65535.0f, 0};
  output.v2.quantizeParams = input.v2.quantizeParams;
  return validate(backend, op) == QNN_SUCCESS ? 0 : -1;
}
} // namespace qnn_ondevice
#endif
