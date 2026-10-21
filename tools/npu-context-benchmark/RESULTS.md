# Device result — 2026-10-21

Device: Lenovo TB320FC, SM8475, Android 15, arm64-v8a.
Model: Real-CUGAN Pro 2x denoise3x FP16, NHWC 256×256 RGB → 512×512 RGB.
QAIRT: 2.49.0.260730. Default graph optimization settings from the shared DLC runtime.

The installed test app recorded these runs, initiated on the device:

| Measurement | Fresh context compilation | Saved-context test |
| --- | ---: | ---: |
| QNN initialization | 139.6 ms | 127.4 ms |
| DLC composition, finalization and serialization | **1716.6 ms** | Skipped |
| Context loading | 52.9 ms | 45.1 ms |
| One synthetic tile, including tensor copies | 115.6 ms | 98.7 ms |
| Total wall time | 2.254 s | 0.477 s |
| Process peak RSS since launch | 423.3 MiB | 423.3 MiB |

Both runs successfully loaded the generated context and executed a tile on HTP.
The generated context is 3,735,456 bytes. The 2,662,544-byte DLC contains FP16
input, output, weights and intermediate tensors, with no prepared context cache
(`qairt-dlc-info` inspected before installation).

This is one compilation and one subsequent cached run in the same app process;
it is not a repeated statistical benchmark or a reboot-cold storage benchmark.
Synthetic output shape and finiteness were checked; image quality was not evaluated.
The compile measurement excludes SDK conversion on the PC and model extraction.
The full device log is saved locally in ignored `build/device-results.txt`.

DLC SHA-256: `bd262eaee6da10a7f778a10ac5c376e6f54272d52a96d19ca8ca1e60c7863a0f`.

## Real-image validation of the generated context

The context was extracted byte-for-byte from the installed app, with its SHA-256
checked before and after validation:
`f50190c55bf52d6addf2168d31b54805c0a3fe151ebc03cef59efa54140a513e`.
It was loaded by QNN 2.49 on the same SM8475 HTP device without recompiling.

Seven real image tiles were tested: five manga tiles from different OpenMantra
titles and two color photograph tiles from BSDS500. Every input was 256×256 RGB;
every output was 512×512 RGB. All outputs were finite, nonconstant, and within [0,1].

| Sample | Domain | PSNR vs original FP32 ONNX | MAE vs FP32 ONNX |
| --- | --- | ---: | ---: |
| 0 | Manga | 49.30 dB | 0.001740 |
| 1 | Manga | 48.13 dB | 0.001514 |
| 2 | Manga | 48.11 dB | 0.001372 |
| 3 | Manga | 45.00 dB | 0.002833 |
| 4 | Manga | 47.79 dB | 0.001264 |
| 5 | Color photo | 53.96 dB | 0.001221 |
| 6 | Color photo | 47.90 dB | 0.002369 |

All seven outputs were also **byte-identical** to outputs from the existing,
offline-generated FP16 v69 context (each raw output's SHA-256 was compared).
The device-generated and offline-generated context binaries have different hashes,
but produced identical results for these inputs.

The contact sheet was inspected: no layout distortion or abnormal color channels
were observed relative to the ONNX output. The FP32 ONNX is a numerical reference,
not high-resolution photographic ground truth. These tests establish usability
for the model's fixed-size tiles; full-page tiling/stitching was not tested here.

Artifacts: ignored `build/validation/report.json`, `comparison.png`, input/output
PNGs, raw outputs and QNN logs. Validation command is documented in README.md.
