# N3 Reference — Artifact Report

Phase N3 evidence bundle (see `docs/exynos-ai/N3_REFERENCE_CORRECTNESS.md` for the
conclusions and `docs/exynos-ai/N3_REFERENCE_CONTRACT.md` for provenance).

## Contents

| Path | Description |
|---|---|
| `summary.json` | Full structured metrics: candidate table, per-fixture, image-domain. |
| `comparison.csv` | Per (fixture × candidate) metric rows. |
| `fixtures/` | Canonical inputs (`*_input.f32le`, `*.png`) + `fixtures_manifest.json` (hashes/stats). |
| `reference/` | PyTorch FP32 reference outputs (GENERAL retained gzipped) + `reference_manifest.json` (all candidates' hashes). |
| `reference_fp16/` | (absent on this host — no CUDA; FP16 secondary was run ad-hoc, recorded in the report). |
| `device_raw/` | Samsung NNC raw FP32 D2H outputs (run 1, gzipped) + `metadata.json` (input parity, repeatability, NPU proof). |
| `diff/` | Amplitude-normalized per-channel abs-diff images (NNC vs candidate). |
| `n2_regression/` | N2 FP16 probe re-run with the final code (OpenModel/Allocate/Execute/NPU-proof/close PASS). |
| `raw_evidence_manifest.json` | SHA-256 of every retained (gzipped) raw file and of its decompressed original. |

## Size policy

Raw tensor dumps are gzip-compressed; regenerable reference tensors (WDN/DNI raw, all
`.npy`) were dropped and are reproducible via `tools/exynos_reference/reference_runner.py`
with the pinned checkpoints (hashes in `reference_manifest.json`). The irreplaceable
on-device NNC outputs are retained gzipped (run 1 only; run-1 == run-2 bit-identically,
per `device_raw/metadata.json`).

## Regeneration

```
python tools/exynos_reference/generate_fixtures.py <fixtures dir>
python tools/exynos_reference/reference_runner.py --weights-dir <dir> --fixtures-dir <fixtures> --out-dir <this dir>
# capture NNC raw: run ExynosN3RawReferenceInstrumentationTest, pull to device_raw/
python tools/exynos_reference/compare.py --base <this dir>
```