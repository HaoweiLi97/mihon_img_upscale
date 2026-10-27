#!/usr/bin/env python3
"""Reject a DLC with floating-point or wider activation/weight tensors."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dlc", type=Path)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("QNN_SDK_ROOT", "/qnn")))
    args = parser.parse_args()
    result = subprocess.run(
        [sys.executable, str(args.sdk / "bin/x86_64-linux-clang/qairt-dlc-info"),
         "--input_dlc", str(args.dlc)],
        check=True, capture_output=True, text=True,
    )
    tensors = re.findall(
        r"([^|\n]+?) \(data type: ([^;]+); tensor dimension: [^;]+; tensor type: ([^)]+)\)",
        result.stdout,
    )
    if not tensors or not any(kind == "APP_READ" for _, _, kind in tensors):
        raise ValueError("DLC inspector returned no input/output tensor metadata")
    for name, dtype, kind in tensors:
        allowed = ("uFxp_8", "sFxp_8", "sFxp_32") if kind == "STATIC" else ("uFxp_8", "sFxp_8")
        if dtype not in allowed:
            raise ValueError(f"DLC is not W8A8: {kind} tensor uses {dtype}")
        if dtype == "sFxp_32" and "bias" not in name.lower():
            raise ValueError(f"Only biases may use INT32: {name.strip()}")
    if not any(dtype == "sFxp_8" and kind == "STATIC" for _, dtype, kind in tensors):
        raise ValueError("DLC has no 8-bit convolution weights")
    print(f"Verified W8A8 tensors and INT32 biases: {args.dlc.name}")


if __name__ == "__main__":
    main()
