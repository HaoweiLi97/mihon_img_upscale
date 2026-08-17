#!/usr/bin/env python3

import argparse
import importlib.util
import struct
from pathlib import Path

import numpy as np
import onnx
import torch
from torch import nn
from torch.nn import functional as functional


class SrvggNetCompact(nn.Module):
    def __init__(self, num_conv: int) -> None:
        super().__init__()
        layers: list[nn.Module] = [nn.Conv2d(3, 64, 3, 1, 1), nn.PReLU(num_parameters=64)]
        for _ in range(num_conv):
            layers.extend((nn.Conv2d(64, 64, 3, 1, 1), nn.PReLU(num_parameters=64)))
        layers.append(nn.Conv2d(64, 3 * 4 * 4, 3, 1, 1))
        self.body = nn.Sequential(*layers)

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        residual = functional.interpolate(image, scale_factor=4, mode="nearest")
        return functional.pixel_shuffle(self.body(image), 4) + residual


class RealEsrgan(nn.Module):
    def __init__(self, weights: Path, scale: int, num_conv: int) -> None:
        super().__init__()
        self.model = SrvggNetCompact(num_conv)
        self.scale = scale
        checkpoint = torch.load(weights, map_location="cpu", weights_only=True)
        params = checkpoint.get("params_ema", checkpoint.get("params"))
        if params is None:
            raise RuntimeError(f"No params or params_ema weights in {weights}")
        self.model.load_state_dict(params, strict=True)

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        output_x4 = self.model(image)
        output = output_x4
        if self.scale != 4:
            output = functional.interpolate(
                output_x4,
                scale_factor=self.scale / 4,
                mode="bilinear",
                align_corners=False,
            )
        return torch.clamp(output, 0, 1)


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


def load_ncnn_weights(model: nn.Module, model_prefix: Path) -> None:
    weighted_layers: list[tuple[str, int, int]] = []
    lines = model_prefix.with_suffix(".param").read_text().splitlines()[2:]
    for line in lines:
        fields = line.split()
        layer_type = fields[0]
        if layer_type not in ("Convolution", "Deconvolution", "InnerProduct"):
            continue
        parameter_start = 4 + int(fields[2]) + int(fields[3])
        parameters = {
            int(key): value
            for field in fields[parameter_start:]
            if "=" in field
            for key, value in (field.split("=", 1),)
        }
        weight_key = 2 if layer_type == "InnerProduct" else 6
        bias_key = 1 if layer_type == "InnerProduct" else 5
        weighted_layers.append(
            (layer_type, int(parameters[weight_key]), int(parameters.get(bias_key, "0"))),
        )

    state = model.state_dict()
    state_items = list(state.items())
    if len(state_items) != len(weighted_layers) * 2:
        raise RuntimeError(
            f"NCNN layer count does not match PyTorch model: {len(weighted_layers)} layers, "
            f"{len(state_items)} tensors",
        )

    with model_prefix.with_suffix(".bin").open("rb") as stream:
        loaded: dict[str, torch.Tensor] = {}
        for index, (layer_type, weight_count, has_bias) in enumerate(weighted_layers):
            weight_name, weight_template = state_items[index * 2]
            bias_name, bias_template = state_items[index * 2 + 1]
            if not weight_name.endswith(".weight") or not bias_name.endswith(".bias") or not has_bias:
                raise RuntimeError(f"Unexpected state layout at NCNN layer {index}: {weight_name}, {bias_name}")
            if weight_template.numel() != weight_count:
                raise RuntimeError(
                    f"Weight size mismatch for {weight_name}: NCNN={weight_count}, "
                    f"PyTorch={weight_template.numel()}",
                )

            tag_bytes = stream.read(4)
            if len(tag_bytes) != 4 or struct.unpack("<I", tag_bytes)[0] != 0x01306B47:
                raise RuntimeError(f"Expected FP16 NCNN weights for {weight_name}")
            weights = np.frombuffer(stream.read(weight_count * 2), dtype="<f2").astype(np.float32)
            if weights.size != weight_count:
                raise RuntimeError(f"Truncated NCNN weights for {weight_name}")
            if layer_type == "Deconvolution":
                shape = tuple(weight_template.shape)
                weights = weights.reshape(shape[1], shape[0], *shape[2:]).transpose(1, 0, 2, 3)
            else:
                weights = weights.reshape(tuple(weight_template.shape))

            bias_bytes = stream.read(bias_template.numel() * 4)
            biases = np.frombuffer(bias_bytes, dtype="<f4").copy()
            if biases.size != bias_template.numel():
                raise RuntimeError(f"Truncated NCNN biases for {bias_name}")
            loaded[weight_name] = torch.from_numpy(weights.copy())
            loaded[bias_name] = torch.from_numpy(biases.reshape(tuple(bias_template.shape)))

        if stream.read(1):
            raise RuntimeError(f"Unexpected trailing data in {model_prefix.with_suffix('.bin')}")
    model.load_state_dict(loaded, strict=True)


class RealCugan(nn.Module):
    PADDING = {2: 18, 3: 14, 4: 19}

    def __init__(self, source: Path, scale: int, weights: Path | None = None, ncnn_model: Path | None = None) -> None:
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
        if weights is not None:
            checkpoint = torch.load(weights, map_location="cpu", weights_only=True)
            self.model.load_state_dict(checkpoint, strict=True)
        elif ncnn_model is not None:
            load_ncnn_weights(self.model, ncnn_model)
        else:
            raise ValueError("Real-CUGAN requires PyTorch weights or an NCNN model")

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


class Span(nn.Module):
    def __init__(self, weights: Path) -> None:
        super().__init__()
        try:
            from spandrel import ModelLoader
        except ImportError as error:
            raise RuntimeError("SPAN export requires the spandrel package") from error
        descriptor = ModelLoader().load_from_file(weights)
        if descriptor.scale != 2 or descriptor.input_channels != 3 or descriptor.output_channels != 3:
            raise RuntimeError("Expected a 2x RGB SPAN image model")
        self.model = descriptor.model

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        return torch.clamp(self.model(image), 0, 1)


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
    parser.add_argument("--realesrgan-weights", type=Path)
    parser.add_argument("--realesrgan-general-weights", type=Path)
    parser.add_argument("--realcugan-source", type=Path)
    parser.add_argument("--realcugan-weights-dir", type=Path)
    parser.add_argument("--realcugan-pro-model-dir", type=Path)
    parser.add_argument("--span-weights", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--tile-size", type=int, default=128)
    parser.add_argument("--scales", type=int, nargs="+", choices=(2, 3, 4), default=(2, 3, 4))
    args = parser.parse_args()

    if not any(
        (
            args.realesrgan_weights,
            args.realesrgan_general_weights,
            args.realcugan_weights_dir,
            args.realcugan_pro_model_dir,
            args.span_weights,
        ),
    ):
        parser.error("Provide at least one model source")

    if args.realesrgan_weights:
        for scale in args.scales:
            export(
                RealEsrgan(args.realesrgan_weights, scale, num_conv=16),
                args.output_dir / f"realesrgan-animevideov3-x{scale}.onnx",
                args.tile_size,
                scale,
            )

    if args.realesrgan_general_weights and 2 in args.scales:
        export(
            RealEsrgan(args.realesrgan_general_weights, 2, num_conv=32),
            args.output_dir / "realesrgan-general-x4v3-x2.onnx",
            args.tile_size,
            2,
        )

    if args.realcugan_weights_dir and not args.realcugan_source:
        parser.error("Real-CUGAN SE export requires --realcugan-source")
    if args.realcugan_weights_dir:
        for scale in args.scales:
            variants = (
                ("no-denoise", "denoise3x", "conservative")
                if scale > 2
                else ("no-denoise", "denoise1x", "denoise2x", "denoise3x", "conservative")
            )
            for variant in variants:
                weights = args.realcugan_weights_dir / f"up{scale}x-latest-{variant}.pth"
                export(
                    RealCugan(args.realcugan_source, scale, weights=weights),
                    args.output_dir / f"realcugan-se-x{scale}-{variant}.onnx",
                    args.tile_size,
                    scale,
                )

    if args.realcugan_pro_model_dir:
        if not args.realcugan_source:
            parser.error("Real-CUGAN Pro export requires --realcugan-source")
        for scale in (candidate for candidate in args.scales if candidate in (2, 3)):
            for variant in ("no-denoise", "denoise3x", "conservative"):
                model_prefix = args.realcugan_pro_model_dir / f"up{scale}x-{variant}"
                export(
                    RealCugan(args.realcugan_source, scale, ncnn_model=model_prefix),
                    args.output_dir / f"realcugan-pro-x{scale}-{variant}.onnx",
                    args.tile_size,
                    scale,
                )

    if args.span_weights:
        export(
            Span(args.span_weights),
            args.output_dir / "span-nomosuni-x2.onnx",
            args.tile_size,
            2,
        )


if __name__ == "__main__":
    main()
