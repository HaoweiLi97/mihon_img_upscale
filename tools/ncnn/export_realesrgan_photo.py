#!/usr/bin/env python3
"""Rebuild the bundled BGR NCNN Photo model from the official RGB checkpoint.

Requires numpy and torch. Run from any directory; weights stay in the build dir.
"""

import argparse
import hashlib
import io
import struct
import urllib.request
from pathlib import Path

import numpy as np
import torch

SOURCE_URL = (
    "https://github.com/xinntao/Real-ESRGAN/releases/download/"
    "v0.2.5.0/realesr-general-x4v3.pth"
)
SOURCE_SHA256 = "8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292"
REPO = Path(__file__).resolve().parents[2]


def export(checkpoint: Path, destination: Path) -> None:
    if hashlib.sha256(checkpoint.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Checkpoint does not match the official general-x4v3 weights")
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)["params"]
    expected_keys = set()
    layers = []
    weights = io.BytesIO()

    def layer(kind, name, inputs, output, params=""):
        layers.append(f"{kind} {name} {len(inputs)} 1 {' '.join(inputs)} {output} {params}".rstrip())

    layer("Input", "input", [], "data")
    layers.append("Split split_input 1 2 data residual features")
    previous = "features"
    for index in range(67):
        name = f"body.{index}"
        weight_key = f"{name}.weight"
        expected_keys.add(weight_key)
        weight = state[weight_key].numpy()
        if index % 2 == 0:
            channels = 48 if index == 66 else 64
            input_channels = 3 if index == 0 else 64
            if weight.shape != (channels, input_channels, 3, 3):
                raise ValueError(f"Unexpected shape for {weight_key}: {weight.shape}")
            bias_key = f"{name}.bias"
            expected_keys.add(bias_key)
            bias = state[bias_key].numpy()
            if bias.shape != (channels,):
                raise ValueError(f"Unexpected shape for {bias_key}: {bias.shape}")
            # The shared Android pre/post pipeline uses BGR. Adapt both ends
            # of the RGB network, including the 16 subpixels of each channel.
            if index == 0:
                weight = weight[:, ::-1]
            if index == 66:
                weight = weight.reshape(3, 16, 64, 3, 3)[::-1].reshape(weight.shape)
                bias = bias.reshape(3, 16)[::-1].reshape(bias.shape)
            weights.write(struct.pack("<I", 0x01306B47))
            weights.write(weight.astype("<f2").tobytes())
            weights.write(bias.astype("<f4").tobytes())
            layer("Convolution", name, [previous], name, f"0={channels} 1=3 4=1 5=1 6={weight.size}")
        else:
            if weight.shape != (64,):
                raise ValueError(f"Unexpected PReLU shape: {weight.shape}")
            weights.write(weight.astype("<f4").tobytes())
            layer("PReLU", name, [previous], name, "0=64")
        previous = name
    if set(state) != expected_keys:
        raise ValueError("Checkpoint contains unexpected tensors")
    layer("PixelShuffle", "upscale", [previous], "upscaled", "0=4")
    layer("Interp", "residual_upscale", ["residual"], "residual_x4", "0=1 1=4 2=4")
    layer("BinaryOp", "add_residual", ["upscaled", "residual_x4"], "output_x4", "0=0")
    layer("Interp", "output_resize", ["output_x4"], "output", "0=3 1=0.5 2=0.5")
    binary = weights.getvalue()
    if len(binary) != 2_435_272:
        raise ValueError(f"Unexpected NCNN weight size: {len(binary)}")
    destination.mkdir(parents=True, exist_ok=True)
    # Binary writes are essential: newline conversion corrupts weight offsets.
    (destination / "x4.bin").write_bytes(binary)
    (destination / "x4.param").write_bytes((f"7767517\n{len(layers)} {len(layers) + 1}\n" + "\n".join(layers) + "\n").encode())
    print(f"x4.bin: {len(binary)} bytes, SHA256 {hashlib.sha256(binary).hexdigest()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, default=REPO / "build/real-esrgan-photo/realesr-general-x4v3.pth")
    parser.add_argument("--output-dir", type=Path, default=REPO / "app/src/main/assets/realesrgan-models/v3-general")
    parser.add_argument("--download", action="store_true", help="Fetch a fresh official checkpoint")
    args = parser.parse_args()
    if args.download or not args.checkpoint.exists():
        args.checkpoint.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(SOURCE_URL, args.checkpoint)
    export(args.checkpoint, args.output_dir)


if __name__ == "__main__":
    main()
