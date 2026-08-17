#!/usr/bin/env python3

import argparse
import importlib.util
from pathlib import Path

import onnx
import torch
from torch import nn
from torch.nn import functional as functional


class SrvggNetCompact(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        layers: list[nn.Module] = [nn.Conv2d(3, 64, 3, 1, 1), nn.PReLU(num_parameters=64)]
        for _ in range(16):
            layers.extend((nn.Conv2d(64, 64, 3, 1, 1), nn.PReLU(num_parameters=64)))
        layers.append(nn.Conv2d(64, 3 * 4 * 4, 3, 1, 1))
        self.body = nn.Sequential(*layers)

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        residual = functional.interpolate(image, scale_factor=4, mode="nearest")
        return functional.pixel_shuffle(self.body(image), 4) + residual


class RealEsrgan(nn.Module):
    def __init__(self, weights: Path, scale: int) -> None:
        super().__init__()
        self.model = SrvggNetCompact()
        self.scale = scale
        checkpoint = torch.load(weights, map_location="cpu", weights_only=True)
        self.model.load_state_dict(checkpoint["params"], strict=True)

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        output_x4 = self.model(image)
        if self.scale == 4:
            return output_x4
        return functional.interpolate(
            output_x4,
            scale_factor=self.scale / 4,
            mode="bilinear",
            align_corners=False,
        )


class _CuganFunctionalProxy:
    def __getattr__(self, name: str):
        return getattr(functional, name)

    @staticmethod
    def pad(image: torch.Tensor, padding, *args, **kwargs) -> torch.Tensor:
        if len(padding) == 4 and all(value <= 0 for value in padding):
            left, right, top, bottom = padding
            row_end = bottom if bottom < 0 else None
            column_end = right if right < 0 else None
            return image[..., -top:row_end, -left:column_end]
        return functional.pad(image, padding, *args, **kwargs)


class RealCugan(nn.Module):
    PADDING = {2: 18, 3: 14, 4: 19}

    def __init__(self, source: Path, weights: Path, scale: int) -> None:
        super().__init__()
        spec = importlib.util.spec_from_file_location("realcugan_upcunet", source)
        if spec is None or spec.loader is None:
            raise RuntimeError(f"Unable to load Real-CUGAN source: {source}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.F = _CuganFunctionalProxy()

        model_class = getattr(module, f"UpCunet{scale}x")
        self.model = model_class()
        self.scale = scale
        checkpoint = torch.load(weights, map_location="cpu", weights_only=True)
        self.model.load_state_dict(checkpoint, strict=True)

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        padding = self.PADDING[self.scale]
        padded = functional.pad(image, (padding, padding, padding, padding), mode="reflect")
        first_pass = self.model.unet1.forward(padded)
        second_pass = self.model.unet2.forward(first_pass, alpha=1)
        first_pass = first_pass[..., 20:-20, 20:-20]
        output = second_pass + first_pass
        if self.scale == 4:
            output = self.model.conv_final(output)
            output = output[..., 1:-1, 1:-1]
            output = self.model.ps(output)
            output = output + functional.interpolate(image, scale_factor=4, mode="nearest")
        return torch.clamp(output, 0, 1)


def export(model: nn.Module, output: Path, tile_size: int, scale: int) -> None:
    model.eval()
    output.parent.mkdir(parents=True, exist_ok=True)
    sample = torch.zeros((1, 3, tile_size, tile_size), dtype=torch.float32)
    with torch.inference_mode():
        actual = model(sample)
    expected_shape = (1, 3, tile_size * scale, tile_size * scale)
    if tuple(actual.shape) != expected_shape:
        raise RuntimeError(f"Unexpected output shape {tuple(actual.shape)}, expected {expected_shape}")

    torch.onnx.export(
        model,
        sample,
        output,
        input_names=["input"],
        output_names=["output"],
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
    )
    onnx_model = onnx.load(output)
    onnx.checker.check_model(onnx_model)
    print(f"Exported {output} ({output.stat().st_size} bytes)")


def main() -> None:
    parser = argparse.ArgumentParser(description="Export Mihon 2x enhancement models for QNN conversion")
    parser.add_argument("--realesrgan-weights", type=Path, required=True)
    parser.add_argument("--realcugan-source", type=Path, required=True)
    parser.add_argument("--realcugan-weights-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--tile-size", type=int, default=128)
    parser.add_argument("--scales", type=int, nargs="+", choices=(2, 3, 4), default=(2, 3, 4))
    args = parser.parse_args()

    for scale in args.scales:
        export(
            RealEsrgan(args.realesrgan_weights, scale),
            args.output_dir / f"realesrgan-animevideov3-x{scale}.onnx",
            args.tile_size,
            scale,
        )

        variants = (
            ("no-denoise", "denoise3x", "conservative")
            if scale > 2
            else ("no-denoise", "denoise1x", "denoise2x", "denoise3x", "conservative")
        )
        for variant in variants:
            weights = args.realcugan_weights_dir / f"up{scale}x-latest-{variant}.pth"
            export(
                RealCugan(args.realcugan_source, weights, scale),
                args.output_dir / f"realcugan-se-x{scale}-{variant}.onnx",
                args.tile_size,
                scale,
            )


if __name__ == "__main__":
    main()
