#!/usr/bin/env python3
"""Package existing calibration crops as authenticated, encrypted release assets.

Requires numpy and cryptography. The key stays outside Git and release assets.
Models, encodings and compiled contexts are intentionally absent from these packs.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import urllib.request
import zipfile

import numpy as np
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"MIHQCAL1"
REPOSITORY = "HaoweiLi97/mihon_img_upscale"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def rgb_bytes(path, nchw):
    values = np.fromfile(path, dtype="<f4")
    if values.size != 256 * 256 * 3 or not np.isfinite(values).all():
        raise ValueError(f"Invalid calibration tensor: {path.name}")
    if nchw:
        values = values.reshape(3, 256, 256).transpose(1, 2, 0).copy()
    else:
        values = values.reshape(256, 256, 3)
    if values.min() < 0 or values.max() > 1:
        raise ValueError(f"Calibration values outside [0,1]: {path.name}")
    pixels = np.rint(values * 255).astype(np.uint8)
    if np.max(np.abs(pixels.astype(np.float32) / 255 - values)) > 1e-7:
        raise ValueError(f"Calibration data is not losslessly representable as RGB8: {path.name}")
    return pixels.tobytes()


def build_pack(root, domain, version, key, license_text):
    manifest = {"schema": 1, "domain": domain, "version": version,
                "tileSize": 256, "layout": "NHWC", "format": "RGB_U8", "files": []}
    content = io.BytesIO()
    with zipfile.ZipFile(content, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for role, folder, prefix, nchw in [
            ("calibration", root / f"calibration-{domain}-nhwc", "input", False),
            ("validation", root / f"validation-{domain}", "verify", True),
        ]:
            index = json.loads((folder / "index.json").read_text(encoding="utf-8"))
            if role == "validation":
                # Disjoint, evenly distributed held-out crops; never used to fit encodings.
                index = [index[int(i)] for i in np.linspace(0, len(index) - 1, 8, dtype=int)]
            for i, item in enumerate(index):
                name = f"{prefix}-{i:03}.rgb"
                data = rgb_bytes(folder / item["file"], nchw)
                archive.writestr(name, data)
                manifest["files"].append({
                    "file": name, "role": role, "bytes": len(data), "sha256": digest(data),
                    "source": item.get("source"), "source_sha256": item.get("source_sha256"),
                    "crop": item.get("crop"), "resize_factor": item.get("resize_factor"),
                    "jpeg_quality": item.get("jpeg_quality"),
                })
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        if domain == "manga":
            archive.writestr("LICENSE.OpenMantra.txt", license_text)
            attribution = (
                "OpenMantra dataset, Mantra Inc. / the original manga creators.\n"
                "https://github.com/mantra-inc/open-mantra-dataset\n"
                "Creative Commons Attribution-NonCommercial 4.0 International.\n"
                "Changes: resize, crop, optional JPEG recompression, RGB8/NHWC conversion.\n"
                "Ryota Hinami et al., Towards Fully Automated Manga Translation, AAAI 2021.\n"
            )
        else:
            attribution = (
                "BSDS500 images from UC Berkeley Computer Vision Group.\n"
                "https://www2.eecs.berkeley.edu/Research/Projects/CS/vision/grouping/resources.html\n"
                "Original images retain their original copyright and usage conditions.\n"
                "Changes: resize, crop, optional JPEG recompression, RGB8/NHWC conversion.\n"
                "P. Arbelaez et al., Contour Detection and Hierarchical Image Segmentation, TPAMI 2011.\n"
            )
        archive.writestr("ATTRIBUTION.txt", attribution)
    nonce = os.urandom(12)
    aad = f"MIHQCAL1:{domain}:{version}".encode()
    encrypted = MAGIC + nonce + AESGCM(key).encrypt(nonce, content.getvalue(), aad)
    # Verify the exact bytes to be published, including the authentication tag.
    decrypted = AESGCM(key).decrypt(encrypted[8:20], encrypted[20:], aad)
    with zipfile.ZipFile(io.BytesIO(decrypted)) as archive:
        if archive.testzip() is not None:
            raise ValueError("Corrupt calibration archive")
        for item in manifest["files"]:
            if digest(archive.read(item["file"])) != item["sha256"]:
                raise ValueError("Sample checksum mismatch")
    return encrypted, manifest, aad.decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--key-file", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--version", default="v1")
    args = parser.parse_args()
    if not re.fullmatch(r"v[0-9]+", args.version):
        parser.error("Version must have the form v1")
    args.key_file.parent.mkdir(parents=True, exist_ok=True)
    if not args.key_file.exists():
        with args.key_file.open("xb") as output:
            output.write(AESGCM.generate_key(bit_length=256))
    key = args.key_file.read_bytes()
    if len(key) != 32:
        raise ValueError("AES key must contain exactly 32 bytes")
    license_url = (
        "https://raw.githubusercontent.com/mantra-inc/open-mantra-dataset/"
        "59341ad922284a8be219b7784feb5a79779a1447/LICENSE.md"
    )
    with urllib.request.urlopen(license_url, timeout=30) as response:
        license_text = response.read().decode("utf-8")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    catalog = {"schema": 1, "version": args.version, "keyId": digest(key)[:16], "packs": {}}
    for domain in ("manga", "photo"):
        data, manifest, aad = build_pack(args.data_root, domain, args.version, key, license_text)
        name = f"qnn-calibration-{domain}-{args.version}.aes"
        (args.output_dir / name).write_bytes(data)
        catalog["packs"][domain] = {
            "file": name, "bytes": len(data), "sha256": digest(data), "aad": aad,
            "calibrationSamples": sum(f["role"] == "calibration" for f in manifest["files"]),
            "validationSamples": sum(f["role"] == "validation" for f in manifest["files"]),
            "url": f"https://github.com/{REPOSITORY}/releases/download/npu-calibration-{args.version}/{name}",
        }
        print(f"{name}: {len(data)} encrypted bytes, authenticated and sample checksums verified")
    text = json.dumps(catalog, indent=2) + "\n"
    args.catalog.parent.mkdir(parents=True, exist_ok=True)
    args.catalog.write_text(text, encoding="utf-8")
    (args.output_dir / "catalog.json").write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
