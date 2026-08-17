#!/usr/bin/env python3

import argparse
import struct
from pathlib import Path

import numpy as np
import onnx
import torch
from torch import nn
from torch.nn import functional as functional


class PhotoSmall(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        layers: list[nn.Module] = [nn.Conv2d(3, 64, 3, 1, 1), nn.PReLU(64)]
        for _ in range(16):
            layers.extend((nn.Conv2d(64, 64, 3, 1, 1), nn.PReLU(64)))
        layers.append(nn.Conv2d(64, 12, 3, 1, 1))
        self.body = nn.Sequential(*layers)

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        residual = functional.interpolate(image, scale_factor=2, mode="nearest")
        return torch.clamp(functional.pixel_shuffle(self.body(image), 2) + residual, 0, 1)


def parse_layers(param_path: Path) -> list[tuple[str, dict[int, str]]]:
    lines = [line.split() for line in param_path.read_text().splitlines() if line.strip()]
    if len(lines) < 3 or lines[0] != ["7767517"]:
        raise RuntimeError(f"Unsupported NCNN parameter file: {param_path}")
    layers = []
    for fields in lines[2:]:
        params = {}
        for value in fields[4 + int(fields[2]) + int(fields[3]):]:
            key, raw = value.split("=", 1)
            params[int(key)] = raw
        layers.append((fields[0], params))
    return layers


def read_floats(stream, count: int) -> torch.Tensor:
    raw = stream.read(count * 4)
    if len(raw) != count * 4:
        raise RuntimeError("NCNN weight file ended unexpectedly")
    return torch.from_numpy(np.frombuffer(raw, dtype="<f4").copy())


def load_ncnn_weights(model: PhotoSmall, param_path: Path, weight_path: Path) -> None:
    weighted_layers = [layer for layer in parse_layers(param_path) if layer[0] in ("Convolution", "PReLU")]
    modules = list(model.body)
    if len(weighted_layers) != len(modules):
        raise RuntimeError(f"Expected {len(modules)} weighted layers, found {len(weighted_layers)}")

    with weight_path.open("rb") as stream, torch.no_grad():
        for (layer_type, params), module in zip(weighted_layers, modules, strict=True):
            if layer_type == "Convolution" and isinstance(module, nn.Conv2d):
                tag = stream.read(4)
                if tag != struct.pack("<I", 0):
                    raise RuntimeError(f"Only float32 NCNN convolution weights are supported, found tag {tag.hex()}")
                weight_count = int(params[6])
                if weight_count != module.weight.numel():
                    raise RuntimeError(f"Unexpected convolution weight count: {weight_count}")
                module.weight.copy_(read_floats(stream, weight_count).reshape_as(module.weight))
                if int(params.get(5, "0")) != 1 or module.bias is None:
                    raise RuntimeError("W2xEX Photo Small convolution biases are required")
                module.bias.copy_(read_floats(stream, module.bias.numel()))
            elif layer_type == "PReLU" and isinstance(module, nn.PReLU):
                slope_count = int(params[0])
                if slope_count != module.weight.numel():
                    raise RuntimeError(f"Unexpected PReLU slope count: {slope_count}")
                module.weight.copy_(read_floats(stream, slope_count))
            else:
                raise RuntimeError(f"NCNN and PyTorch layers do not match at {layer_type}")
        if stream.read(1):
            raise RuntimeError("NCNN weight file contains unexpected trailing data")

        first = model.body[0]
        last = model.body[-1]
        first.weight.copy_(first.weight[:, [2, 1, 0]])
        last.weight.copy_(last.weight.reshape(3, 4, *last.weight.shape[1:])[[2, 1, 0]].reshape_as(last.weight))
        last.bias.copy_(last.bias.reshape(3, 4)[[2, 1, 0]].reshape_as(last.bias))


def main() -> None:
    parser = argparse.ArgumentParser(description="Export W2xEX Photo Small from NCNN to RGB ONNX")
    parser.add_argument("--param", type=Path, required=True)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tile-size", type=int, default=256)
    args = parser.parse_args()

    model = PhotoSmall().eval()
    load_ncnn_weights(model, args.param, args.weights)
    sample = torch.zeros((1, 3, args.tile_size, args.tile_size), dtype=torch.float32)
    with torch.inference_mode():
        expected_shape = (1, 3, args.tile_size * 2, args.tile_size * 2)
        if tuple(model(sample).shape) != expected_shape:
            raise RuntimeError("W2xEX Photo Small produced an unexpected output shape")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        sample,
        args.output,
        input_names=["input"],
        output_names=["output"],
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
    )
    onnx_model = onnx.load(args.output)
    onnx.checker.check_model(onnx_model)
    print(f"Exported {args.output} ({args.output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
