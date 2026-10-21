"""Validate the actual Android-generated context against the original FP32 ONNX.

Run with Python, numpy, onnxruntime and Pillow. Accepts Windows paths on Windows,
or /mnt/c paths under WSL (adb.exe transfers are translated back to Windows paths).
Does not regenerate or replace the context in the test app.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import shlex
import subprocess
import time

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--data", required=True, type=Path)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "build/validation")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    remote = "/data/local/tmp/mihon-npu-context-validation"
    model = "realcugan-pro-x2-denoise3x"
    app_file = f"files/{model}-fp16.context.bin"

    def adb(*values, timeout=120):
        completed = subprocess.run([args.adb, "-s", args.serial, *map(str, values)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if completed.returncode:
            raise RuntimeError(completed.stdout.decode(errors="replace") +
                               completed.stderr.decode(errors="replace"))
        return completed.stdout

    def host(path):
        value = str(Path(path).resolve())
        if args.adb.lower().endswith(".exe") and value.startswith("/mnt/"):
            return value[5].upper() + ":/" + value[7:]
        return value

    # Preserve the exact binary compiled in the app, and prove the extraction is byte-exact.
    remote_hash = adb("shell", "run-as", "dev.mihon.npubench", "sha256sum", app_file).decode().split()[0]
    context = output / "context-from-device.bin"
    context.write_bytes(adb("exec-out", "run-as", "dev.mihon.npubench", "cat", app_file))
    digest = hashlib.sha256(context.read_bytes()).hexdigest()
    assert digest == remote_hash, "Context extraction hash mismatch"
    print(f"Extracted actual app context: {context.stat().st_size} bytes, SHA256 {digest}", flush=True)

    adb("shell", "mkdir", "-p", remote)
    libraries = [args.sdk / "bin/aarch64-android/qnn-net-run",
                 args.sdk / "lib/aarch64-android/libQnnHtp.so",
                 args.sdk / "lib/aarch64-android/libQnnSystem.so",
                 args.sdk / "lib/aarch64-android/libQnnHtpV69Stub.so",
                 args.sdk / "lib/hexagon-v69/unsigned/libQnnHtpV69Skel.so"]
    for library in libraries:
        adb("push", host(library), remote + "/" + library.name)
    adb("push", host(context), remote + "/context-from-device.bin")
    adb("shell", "chmod", "755", remote + "/qnn-net-run")

    index = json.loads((args.data / "validation-manga/index.json").read_text())
    samples = []
    seen_titles = set()
    for item in index:
        title = item["source"].split("/")[3]
        if title not in seen_titles:
            seen_titles.add(title)
            samples.append(("manga", item))
        if len(samples) == 5:
            break
    photos = json.loads((args.data / "validation-photo/index.json").read_text())
    samples += [("photo", photos[i]) for i in (0, 20)]
    inputs = []
    input_paths = []
    for i, (domain, item) in enumerate(samples):
        path = args.data / f"validation-{domain}" / item["file"]
        x = np.fromfile(path, np.float32).reshape(1, 3, 256, 256)
        assert np.isfinite(x).all() and x.min() >= 0 and x.max() <= 1
        raw = output / f"input-{i:02d}-nhwc.raw"
        x.transpose(0, 2, 3, 1).copy().tofile(raw)
        adb("push", host(raw), remote + "/" + raw.name)
        input_paths.append(remote + "/" + raw.name)
        inputs.append(x)
    listing = output / "input-list.txt"
    listing.write_text("\n".join(input_paths) + "\n")
    adb("push", host(listing), remote + "/input-list.txt")

    def run_context(filename, tag):
        command = [remote + "/qnn-net-run", "--backend", remote + "/libQnnHtp.so",
                   "--retrieve_context", remote + "/" + filename,
                   "--input_list", remote + "/input-list.txt",
                   "--output_dir", remote + "/" + tag,
                   "--perf_profile", "balanced", "--log_level", "warn"]
        invocation = ("LD_LIBRARY_PATH=" + shlex.quote(remote) + " ADSP_LIBRARY_PATH=" +
                      shlex.quote(remote + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/cdsp;/system/vendor/lib/rfsa/cdsp") +
                      " " + shlex.join(command))
        started = time.perf_counter()
        log = adb("shell", invocation)
        (output / f"{tag}.log").write_bytes(log)
        local = output / tag
        local.mkdir(exist_ok=True)
        for i in range(len(samples)):
            (local / f"output-{i:02d}.raw").write_bytes(adb(
                "exec-out", "cat", f"{remote}/{tag}/Result_{i}/output.raw"))
        print(f"{tag}: {len(samples)} real tiles executed successfully", flush=True)
        return local, time.perf_counter() - started

    actual_dir, seconds = run_context("context-from-device.bin", "device-context")
    offline = args.data / "active-contexts" / f"{model}.v69.bin"
    adb("push", host(offline), remote + "/offline-context.bin")
    offline_dir, _ = run_context("offline-context.bin", "offline-context")

    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    onnx = args.data / "onnx" / f"{model}.onnx"
    session = ort.InferenceSession(str(onnx), options, providers=["CPUExecutionProvider"])
    rows = []
    previews = []
    for i, ((domain, item), x) in enumerate(zip(samples, inputs)):
        reference = session.run(None, {"input": x})[0].transpose(0, 2, 3, 1)[0]
        actual = np.fromfile(actual_dir / f"output-{i:02d}.raw", np.float32).reshape(512, 512, 3)
        existing = np.fromfile(offline_dir / f"output-{i:02d}.raw", np.float32).reshape(512, 512, 3)
        finite = bool(np.isfinite(actual).all())
        difference = actual - reference
        mse = float(np.mean(difference.astype(np.float64) ** 2))
        psnr = -10 * math.log10(max(mse, 1e-16))
        mae = float(np.mean(np.abs(difference)))
        offline_mae = float(np.mean(np.abs(actual - existing)))
        record = dict(index=i, domain=domain, source=item["source"], file=item["file"],
                      finite=finite, psnr_db=psnr, mae=mae,
                      max_abs_error=float(np.max(np.abs(difference))),
                      offline_context_mae=offline_mae,
                      output_min=float(actual.min()), output_max=float(actual.max()),
                      output_std=float(actual.std()))
        # Same quality gates as the project's existing FP16 device validator.
        record["passed"] = bool(finite and psnr >= 30 and mae <= 0.02 and actual.std() >= 0.001
                                and actual.min() >= -0.01 and actual.max() <= 1.01
                                and offline_mae <= 0.02)
        rows.append(record)
        print(f"sample {i} {domain}: PSNR {psnr:.2f} dB; MAE {mae:.6f}; "
              f"offline MAE {offline_mae:.6f}; pass={record['passed']}", flush=True)

        def png(array, name):
            image = Image.fromarray(np.rint(np.clip(array, 0, 1) * 255).astype(np.uint8))
            image.save(output / name)
            return image

        source = png(x.transpose(0, 2, 3, 1)[0], f"sample-{i:02d}-input.png")
        device = png(actual, f"sample-{i:02d}-device.png")
        ref = png(reference, f"sample-{i:02d}-reference.png")
        error = png(np.abs(difference) * 20, f"sample-{i:02d}-error-x20.png")
        if i in (0, 1, 5, 6):
            previews.append((i, domain, psnr, source, device, ref, error))

    sheet = Image.new("RGB", (4 * 512, len(previews) * 566), "#f5f7fa")
    draw = ImageDraw.Draw(sheet)
    for row, (i, domain, psnr, source, device, ref, error) in enumerate(previews):
        y = row * 566
        headings = [f"{i}: {domain} input (nearest 2x)", f"Phone context / PSNR {psnr:.2f} dB",
                    "Original ONNX / CPU FP32", "Absolute difference x20"]
        for col, (heading, image) in enumerate(zip(headings, (
                source.resize((512, 512), Image.Resampling.NEAREST), device, ref, error))):
            draw.text((col * 512 + 12, y + 12), heading, fill="#182537", font_size=20)
            sheet.paste(image, (col * 512, y + 48))
    sheet.save(output / "comparison.png")
    report = dict(device="TB320FC/SM8475", model=model, precision="FP16", context_sha256=digest,
                  offline_context_sha256=hashlib.sha256(offline.read_bytes()).hexdigest(),
                  onnx_sha256=hashlib.sha256(onnx.read_bytes()).hexdigest(),
                  context_bytes=context.stat().st_size, device_run_and_transfer_seconds=seconds,
                  passed=all(item["passed"] for item in rows), samples=rows,
                  scope="Real 256x256 tiles; compares device-compiled context with FP32 ONNX and existing offline FP16 context. Not full-page stitching or perceptual ground truth.")
    (output / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    final_hash = adb("shell", "run-as", "dev.mihon.npubench", "sha256sum", app_file).decode().split()[0]
    assert final_hash == digest, "The app context changed during validation"
    if not report["passed"]:
        raise SystemExit("FAILED quality gates; see report.json")
    print("PASS: all real-image samples passed; app context remained unchanged", flush=True)


if __name__ == "__main__":
    main()
