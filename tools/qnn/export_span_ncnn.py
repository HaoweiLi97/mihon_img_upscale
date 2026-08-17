#!/usr/bin/env python3

import argparse
from pathlib import Path

import pnnx
import torch
from spandrel import ModelLoader


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Export a BGR-compatible SPAN model for Mihon's NCNN pipeline",
    )
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--tile-size", type=int, default=256)
    parser.add_argument("--stem", default="2x-NomosUni-SPAN-multijpg-ldl")
    args = parser.parse_args()

    descriptor = ModelLoader().load_from_file(args.weights)
    if descriptor.scale != 2 or descriptor.input_channels != 3 or descriptor.output_channels != 3:
        raise RuntimeError("Expected a 2x RGB SPAN image model")

    model = descriptor.model.eval()
    with torch.no_grad():
        model.mean.copy_(model.mean[:, [2, 1, 0]])
        model.conv_1.sk.weight.copy_(model.conv_1.sk.weight[:, [2, 1, 0]])
        model.conv_1.conv[0].weight.copy_(model.conv_1.conv[0].weight[:, [2, 1, 0]])
        final_conv = model.upsampler[0]
        final_conv.weight.copy_(
            final_conv.weight.reshape(3, 4, *final_conv.weight.shape[1:])[[2, 1, 0]].reshape_as(final_conv.weight),
        )
        final_conv.bias.copy_(final_conv.bias.reshape(3, 4)[[2, 1, 0]].reshape_as(final_conv.bias))

    args.output_dir.mkdir(parents=True, exist_ok=True)
    base = args.output_dir / "span_nomosuni_x2"
    sample = torch.rand(1, 3, args.tile_size, args.tile_size)
    pnnx.export(
        model,
        str(base.with_suffix(".pt")),
        sample,
        pnnxparam=f"{base}.pnnx.param",
        pnnxbin=f"{base}.pnnx.bin",
        pnnxpy=f"{base}_pnnx.py",
        pnnxonnx=f"{base}.pnnx.onnx",
        ncnnparam=f"{base}.param",
        ncnnbin=f"{base}.bin",
        ncnnpy=f"{base}_ncnn.py",
        fp16=False,
    )
    Path(f"{base}.param").replace(args.output_dir / f"{args.stem}.param")
    Path(f"{base}.bin").replace(args.output_dir / f"{args.stem}.bin")


if __name__ == "__main__":
    main()
