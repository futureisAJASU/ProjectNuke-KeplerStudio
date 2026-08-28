# N2B — Exynos-2400 Quantized bring-up and FP16 comparison

Date: 2026-08-28. Target: Galaxy S24 SM-S921N (`e1s`), S5E9945 / Exynos 2400.

## Quantized test artifact and hardware gate

The controlled test build pinned the Samsung official portal Quantized
`Real_ESRGAN_General_x4v3` artifact at the existing logical asset path. Its exact identity
was 1,867,776 bytes and SHA-256
`81968b6a2c6963f081c27d4c843c57ebd0de493d3bb7fa706f2872fdb8840196`.
The source asset, APK asset, and device-prepared file all matched that identity.

The artifact reports Root / EVT1, NPUC `v2.4.11.l`, `QUANT_MODE=ASYMM`, and external
FLOAT32 NCHW `1x3x128x128` to `1x3x512x512`. No internal quantization bit width is
claimed.

On the target device, `EnnInitialize`, `EnnOpenModel`, `EnnAllocateAllBuffers`, H2D,
`EnnExecuteModel`, and D2H all returned `0(ENN_RET_SUCCESS)` across cold, warm, and
second-lifecycle runs. Runtime descriptors were FLOAT32 NCHW `128x128x3` input and
`512x512x3` output. `MODEL_COMPILER_NPU` reported `v2.4.11.l`; the NPU proof status was
`OBSERVED`. Both close cycles settled successfully and the registry post-close assertion
confirmed no active session.

Immutable device evidence is preserved in
`artifacts/exynos-n2b-quantized-s24-20260828/`, including `metadata.json`, deterministic
input, output PNG, filtered ENN logcat, and an absolute-difference diagnostic PNG. The
device report path was
`/storage/emulated/0/Android/data/com.projectnuke.keplerstudio/files/exynos_npu_probe`.

## Controlled comparison

The input is identical in both runs (SHA-256
`a6b95c4c51f5c874c443628284669a36e5318d4ee3d162b722f96a9e1ed74a4d`). FP16 is a
comparison baseline, not reference ground truth.

| Measurement | FP16 N2A | Quantized N2B |
|---|---:|---:|
| NNC bytes | 3,112,960 | 1,867,776 |
| Load ms | 183 | 251 |
| Cold ms | 266 | 268 |
| Warm 1 ms | 99 | 100 |
| Warm 2 ms | 102 | 98 |
| Second load ms | 87 | 55 |
| Second run ms | 97 | 93 |
| Close ms (first / second) | 18 / 21 | 21 / 9 |
| Native heap KiB (pre / post-load / post-cold / post-warm / post-close) | 6709 / 6759 / 7799 / 7799 / 7777 | 6713 / 6763 / 7803 / 7803 / 7781 |
| Output PNG bytes | 226,103 | 419,459 |
| Output SHA-256 | `a1da878b98cb725ea710474e769ce974bfd99f5f1e7f55942339321c764bc641` | `fe635f7ec3c43eeaf7475367e7dc742dac3bfd5ae7314caae6f039bcdacd91e0` |
| R min / max / mean | 0 / 255 / 127.4218 | 0 / 240 / 125.4737 |
| G min / max / mean | 0 / 255 / 125.0861 | 0 / 240 / 122.5953 |
| B min / max / mean | 0 / 255 / 124.7477 | 0 / 240 / 123.0748 |

Offline RGB absolute-difference metrics over 512x512 pixels:

| Metric | Value |
|---|---:|
| R MAE | 7.338192 |
| G MAE | 7.015736 |
| B MAE | 7.166191 |
| Overall MAE | 7.173373 |
| RMSE | 9.672243 |
| PSNR | 28.420260 dB |
| Maximum absolute channel difference | 101 |
| Differing pixels | 261,724 / 262,144 |
| Differing pixel fraction | 0.9983978271 |

## Production decision

**KEEP FP16.** Quantized is substantially smaller and completes all hardware lifecycle
gates, but its warm timing and native heap do not establish a material advantage and its
output divergence from the accepted FP16 run is not tiny. This is not a reference-quality
judgment; N3 remains the separate correctness-validation phase. The active bundled NNC and
`ModelAssetManifest` therefore remain byte-pinned to the accepted FP16 artifact:
3,112,960 bytes, SHA-256
`9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12`.
