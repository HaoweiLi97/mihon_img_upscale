#!/usr/bin/env python3
"""Convert the existing fixed-shape ONNX models to FP16-weight LiteRT models.

Use the pinned environment in requirements.txt. Export each model in its own process
to release TensorFlow graph memory. Numerical checks run on CPU, not MediaTek NPU.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys

import numpy as np

MODELS = [
    "realesrgan-animevideov3-x2", "realesrgan-general-x4v3-x2",
    *[f"realcugan-se-x2-{v}" for v in ("no-denoise", "denoise1x", "denoise2x", "denoise3x", "conservative")],
    *[f"realcugan-pro-x{s}-{v}" for s in (2, 3) for v in ("no-denoise", "denoise3x", "conservative")],
    "span-nomosuni-x2", "w2xex-photo-small-x2",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=MODELS)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if not args.model:
        for model in MODELS:
            subprocess.run([sys.executable, __file__, "--data", str(args.data),
                            "--output", str(args.output), "--model", model], check=True)
        reports = [json.loads((args.output / f"{m}.json").read_text()) for m in MODELS]
        (args.output / "validation.json").write_text(json.dumps(reports, indent=2))
        return

    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")
    os.environ.setdefault("CUDA_VISIBLE_DEVICES", "-1")
    os.environ.setdefault("OMP_NUM_THREADS", "4")
    os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "4")
    os.environ.setdefault("TF_NUM_INTEROP_THREADS", "1")
    import onnxruntime as ort
    from ai_edge_litert.interpreter import Interpreter

    model = args.model
    domain = "photo" if model in ("realesrgan-general-x4v3-x2", "span-nomosuni-x2", "w2xex-photo-small-x2") else "manga"
    source = args.data / f"validation-{domain}/input-000.raw"
    x = np.fromfile(source, np.float32).reshape(1, 3, 256, 256)
    nhwc = x.transpose(0, 2, 3, 1).copy()
    from onnx_tf_bridge import convert_file
    target = args.output / f"{model}.tflite"
    convert_file(args.data / "onnx" / f"{model}.onnx", target)
    interpreter = Interpreter(model_path=str(target), num_threads=4)
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()
    out = interpreter.get_output_details()
    scale = 3 if "-x3-" in model else 2
    assert len(inp) == len(out) == 1
    assert inp[0]["shape"].tolist() == [1, 256, 256, 3] and inp[0]["dtype"] == np.float32
    assert out[0]["shape"].tolist() == [1, 256 * scale, 256 * scale, 3] and out[0]["dtype"] == np.float32
    interpreter.set_tensor(inp[0]["index"], nhwc)
    interpreter.invoke()
    actual = interpreter.get_tensor(out[0]["index"])
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    session = ort.InferenceSession(str(args.data / "onnx" / f"{model}.onnx"), options,
                                 providers=["CPUExecutionProvider"])
    expected = session.run(None, {"input": x})[0].transpose(0, 2, 3, 1)
    difference = actual.astype(np.float64) - expected
    mse = float(np.square(difference).mean())
    mae = float(np.abs(difference).mean())
    psnr = -10 * math.log10(max(mse, 1e-16))
    assert np.isfinite(actual).all() and actual.std() > 0.001
    assert mae <= 0.02 and psnr >= 30, (model, mae, psnr)
    report = dict(model=model, weights="FP16", io="FP32 NHWC", scale=scale,
                  sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                  bytes=target.stat().st_size, cpu_mae=mae, cpu_psnr_db=psnr,
                  validation="CPU LiteRT vs FP32 ONNX; MediaTek NPU not yet tested")
    (args.output / f"{model}.json").write_text(json.dumps(report, indent=2))
    print(f"PASS {model}: CPU PSNR {psnr:.2f} dB; MAE {mae:.6f}", flush=True)


if __name__ == "__main__":
    main()
