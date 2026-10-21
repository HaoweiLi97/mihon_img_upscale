"""Explicit fixed-shape NHWC translation for this repository's 17 ONNX operators.

No layout guessing: activations always use NHWC, while ONNX axes and weights are
translated explicitly. Unsupported operators/attributes fail conversion.
"""
import numpy as np
import onnx
from onnx import numpy_helper
from onnxsim import simplify
import tensorflow as tf


def convert_file(source, target):
    graph, okay = simplify(onnx.load(source))
    assert okay
    constants = {v.name: numpy_helper.to_array(v) for v in graph.graph.initializer}
    nodes = list(graph.graph.node)
    axis = {0: 0, 1: 3, 2: 1, 3: 2, -1: 2, -2: 1, -3: 3, -4: 0}

    class Module(tf.Module):
        @tf.function(input_signature=[tf.TensorSpec([1, 256, 256, 3], tf.float32, name="input")])
        def infer(self, image):
            values = {graph.graph.input[0].name: image}

            def value(name):
                if name in values:
                    return values[name]
                array = constants[name]
                if array.ndim == 4:
                    array = array.transpose(0, 2, 3, 1)
                return tf.constant(array)

            for node in nodes:
                attrs = {a.name: onnx.helper.get_attribute_value(a) for a in node.attribute}
                op = node.op_type
                x = value(node.input[0])
                if op in ("Conv", "ConvTranspose"):
                    assert attrs.get("group", 1) == 1
                    assert attrs.get("auto_pad", b"NOTSET") in (b"NOTSET", b"")
                    stride = attrs.get("strides", [1, 1])
                    dilate = attrs.get("dilations", [1, 1])
                    pads = attrs.get("pads", [0, 0, 0, 0])
                    weights = constants[node.input[1]]
                    if op == "Conv":
                        x = tf.pad(x, [[0, 0], [pads[0], pads[2]], [pads[1], pads[3]], [0, 0]])
                        y = tf.nn.conv2d(x, tf.constant(weights.transpose(2, 3, 1, 0)),
                                         strides=[1, *stride, 1], padding="VALID", dilations=[1, *dilate, 1])
                    else:
                        assert dilate == [1, 1]
                        extra = attrs.get("output_padding", [0, 0])
                        n, h, w, _ = x.shape.as_list()
                        oh = (h - 1) * stride[0] + weights.shape[2] + extra[0]
                        ow = (w - 1) * stride[1] + weights.shape[3] + extra[1]
                        y = tf.nn.conv2d_transpose(x, tf.constant(weights.transpose(2, 3, 1, 0)),
                            [n, oh, ow, weights.shape[1]], strides=[1, *stride, 1], padding="VALID")
                        y = y[:, pads[0]:oh - pads[2], pads[1]:ow - pads[3], :]
                    if len(node.input) > 2:
                        y = tf.nn.bias_add(y, tf.constant(constants[node.input[2]]))
                elif op == "Pad":
                    p = constants[node.input[1]].tolist()
                    mode = attrs.get("mode", b"constant").decode().upper()
                    assert mode in ("CONSTANT", "REFLECT") and len(p) == 8
                    y = tf.pad(x, [[p[0], p[4]], [p[2], p[6]], [p[3], p[7]], [p[1], p[5]]], mode=mode)
                elif op == "Slice":
                    starts, ends = (constants[name].tolist() for name in node.input[1:3])
                    axes = constants[node.input[3]].tolist() if len(node.input) > 3 else list(range(len(starts)))
                    steps = constants[node.input[4]].tolist() if len(node.input) > 4 else [1] * len(starts)
                    slices = [slice(None)] * 4
                    for start, end, a, step in zip(starts, ends, axes, steps):
                        slices[axis[a]] = slice(start, end, step)
                    y = x[tuple(slices)]
                elif op == "ReduceMean":
                    axes = constants[node.input[1]].tolist() if len(node.input) > 1 else attrs["axes"]
                    y = tf.reduce_mean(x, [axis[a] for a in axes], keepdims=bool(attrs.get("keepdims", 1)))
                elif op == "LeakyRelu":
                    y = tf.nn.leaky_relu(x, alpha=attrs.get("alpha", 0.01))
                elif op == "PRelu":
                    slope = tf.constant(constants[node.input[1]].reshape(1, 1, 1, -1))
                    y = tf.maximum(x, 0) + slope * tf.minimum(x, 0)
                elif op == "Relu":
                    y = tf.nn.relu(x)
                elif op == "Sigmoid":
                    y = tf.nn.sigmoid(x)
                elif op in ("Add", "Mul", "Sub"):
                    other = value(node.input[1])
                    y = x + other if op == "Add" else x * other if op == "Mul" else x - other
                elif op == "Concat":
                    y = tf.concat([value(name) for name in node.input], axis=axis[attrs["axis"]])
                elif op == "Clip":
                    low = float(constants[node.input[1]]) if len(node.input) > 1 and node.input[1] else attrs.get("min", -np.inf)
                    high = float(constants[node.input[2]]) if len(node.input) > 2 and node.input[2] else attrs.get("max", np.inf)
                    y = tf.clip_by_value(x, low, high)
                elif op == "Resize":
                    if len(node.input) > 3 and node.input[3]:
                        size = constants[node.input[3]].astype(int).tolist()[2:4]
                    else:
                        scales = constants[node.input[2]]
                        size = [int(x.shape[1] * scales[2]), int(x.shape[2] * scales[3])]
                    mode = attrs.get("mode", b"nearest")
                    coordinate = attrs.get("coordinate_transformation_mode", b"half_pixel")
                    if mode == b"nearest":
                        assert coordinate == b"asymmetric" and attrs.get("nearest_mode", b"floor") == b"floor"
                        y = tf.raw_ops.ResizeNearestNeighbor(images=x, size=size, align_corners=False, half_pixel_centers=False)
                    else:
                        assert mode == b"linear" and coordinate in (b"half_pixel", b"align_corners")
                        y = tf.raw_ops.ResizeBilinear(images=x, size=size, align_corners=coordinate == b"align_corners",
                                                     half_pixel_centers=coordinate == b"half_pixel")
                elif op == "DepthToSpace":
                    block = attrs["blocksize"]
                    if attrs.get("mode", b"DCR") == b"DCR":
                        y = tf.nn.depth_to_space(x, block)
                    else:
                        assert attrs["mode"] == b"CRD"
                        n, h, w, c = x.shape.as_list()
                        co = c // block ** 2
                        y = tf.reshape(x, [n, h, w, co, block, block])
                        y = tf.transpose(y, [0, 1, 4, 2, 5, 3])
                        y = tf.reshape(y, [n, h * block, w * block, co])
                else:
                    raise ValueError(f"Unsupported ONNX op: {op}")
                assert len(node.output) == 1, node.name
                values[node.output[0]] = y
            return {"output": values[graph.graph.output[0].name]}

    module = Module()
    converter = tf.lite.TFLiteConverter.from_concrete_functions([module.infer.get_concrete_function()], module)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    target.write_bytes(converter.convert())
