# Exynos N3 reference validation tooling

Non-production, host-side tooling used by Phase N3 to validate the Samsung Exynos-2400
FP16 NNC against the pinned upstream Real-ESRGAN reference. These scripts are **not** an
Android runtime dependency.

## Pinned environment

| Component | Version | Notes |
|---|---|---|
| Python | 3.13.7 | |
| torch | 2.13.0+cpu | installed via `pip install torch --index-url https://download.pytorch.org/whl/cpu` |
| numpy | 2.3.3 | |
| Pillow | any recent | used only to decode the N2A PNG for the fixture-equality cross-check |
| cv2 | — | deliberately not used (N3 avoids file-decoder/channel ambiguity) |
| basicsr | — | not installed; only `basicsr.utils.registry` is shimmed |

## Layout

```
tools/exynos_reference/
  upstream/srvgg_arch.py      VERBATIM copy of the pinned upstream architecture
                              (xinntao/Real-ESRGAN @ a4abfb29); BSD-3-Clause (LICENSE).
  upstream/LICENSE             upstream BSD-3-Clause license.
  shim/basicsr/utils/registry.py  no-op ARCH_REGISTRY shim (forward path ignores it).
  generate_fixtures.py        deterministic 128x128 RGB corpus + canonical CHW f32le.
  reference_runner.py         loads GENERAL/WDN/DNI-0.5, runs FP32 (and optional --half).
  compare.py                  raw + image-domain metrics, comparison.csv / summary.json.
  reproducible_gzip.py        mtime=0 gzip (de)compress for manifest-hashed evidence containers.
  halo_sweep.py               N4.6 host halo sweep (full-vs-tiled geometry proof).
  generate_n4_fixtures.py     N4 canonical multi-tile fixture inputs (+ manifest).
  n4_reference.py             N4 host full + tiled PyTorch reference generation.
  compare_n4.py               N4 NNC-vs-PyTorch metrics, seam/border, error decomposition.
```

## Evidence-container reproducibility

Any `.gz` container whose hash is recorded in a manifest MUST be produced with a
deterministic compressor, otherwise the gzip header mtime makes the recorded SHA go
stale even when the payload is unchanged. Use:

```
python reproducible_gzip.py compress <payload> <out.gz>
```

which wraps `gzip.compress(..., compresslevel=9, mtime=0)`. The manifest records the
payload (decompressed) SHA (never altered) plus the reproducible container SHA.

## Reproduction

1. Pin upstream:
   ```
   git clone https://github.com/xinntao/Real-ESRGAN.git
   git -C Real-ESRGAN checkout a4abfb2979a7bbff3f69f58f58ae324608821e27
   ```
2. Download the official checkpoints (tag v0.2.5.0) and verify against the hashes in
   `docs/exynos-ai/N3_REFERENCE_CONTRACT.md`.
3. Generate fixtures:
   ```
   python generate_fixtures.py <artifacts/exynos-n3-reference-s24-20260828/fixtures>
   ```
4. Run the FP32 reference over the canonical inputs:
   ```
   python reference_runner.py \
     --weights-dir <dir with the two .pth> \
     --fixtures-dir <fixtures dir> \
     --out-dir <artifacts dir>
   ```
5. Capture the Samsung NNC raw outputs with the on-device instrumentation test
   `ExynosN3RawReferenceInstrumentationTest` (fixtures staged under
   `app/src/androidTest/assets/exynos_n3/`), pull to `artifacts/.../device_raw/`.
6. Compare:
   ```
   python compare.py --base <artifacts dir>
   ```

## Reference input contract (why these are bit-exact)

The fixture `.f32le` files are the exact FP32 CHW planar bytes handed to both platforms:
`float32(uint8_grid / 255.0)` in `(3, H, W)` C-order, which is byte-identical to
`ExynosUpscaleSession.preprocess()` (R plane, G plane, B plane — each row-major
`y*128 + x`). The PyTorch reference reshapes the same bytes to `(1, 3, 128, 128)` and
runs `torch.no_grad()`; the NNC H2Ds the same bytes verbatim. The on-device test hashes the
H2D payload and asserts it equals the fixture hash, proving input parity.