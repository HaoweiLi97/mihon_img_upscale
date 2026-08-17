#!/usr/bin/env python3

import argparse
import random
from pathlib import Path

import numpy as np
from PIL import Image


def main() -> None:
    parser = argparse.ArgumentParser(description="Create QNN RGB/NCHW float32 calibration inputs")
    parser.add_argument("--image-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--tile-size", type=int, default=256)
    parser.add_argument("--samples", type=int, default=64)
    parser.add_argument("--seed", type=int, default=8475)
    args = parser.parse_args()

    images = sorted(path for path in args.image_dir.rglob("*") if path.is_file())
    if not images:
        raise RuntimeError(f"No calibration images found in {args.image_dir}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    randomizer = random.Random(args.seed)
    input_paths: list[Path] = []

    for index in range(args.samples):
        source = images[index % len(images)]
        with Image.open(source) as opened:
            image = opened.convert("RGB")
            scale = max(args.tile_size / image.width, args.tile_size / image.height, 1.0)
            if scale > 1.0:
                image = image.resize(
                    (round(image.width * scale), round(image.height * scale)),
                    Image.Resampling.BICUBIC,
                )
            left = randomizer.randrange(image.width - args.tile_size + 1)
            top = randomizer.randrange(image.height - args.tile_size + 1)
            crop = image.crop((left, top, left + args.tile_size, top + args.tile_size))

        tensor = np.asarray(crop, dtype=np.float32).transpose(2, 0, 1) / 255.0
        output = (args.output_dir / f"input-{index:03d}.raw").resolve()
        tensor.tofile(output)
        input_paths.append(output)

    input_list = args.output_dir / "input-list.txt"
    input_list.write_text("".join(f"{path}\n" for path in input_paths), encoding="utf-8")
    print(f"Created {len(input_paths)} calibration inputs in {args.output_dir}")


if __name__ == "__main__":
    main()
