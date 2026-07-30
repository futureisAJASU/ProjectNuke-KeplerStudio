# On-device open-weight model candidate review

Last reviewed: 2026-07-30

No model weights are bundled by this change. Every candidate must pass a separate license, checkpoint, operator, memory, latency and image-quality gate before it can be packaged or downloaded by the app. A repository license must not be assumed to cover a separately hosted checkpoint unless the project states that clearly.

## Recommended pilot order

| Priority | Task | Candidate | What is available | License/readiness assessment | KeplerStudio recommendation |
|---:|---|---|---|---|---|
| P0 | Interactive masking | EfficientSAM-S / Ti | Official checkpoints; separate encoder/decoder ONNX exports are available | Apache-2.0; practical first ONNX Runtime Mobile experiment | Best first mask pilot. Add point/box prompts, bounded encoder cache and ALPHA_8 output. |
| P0 | Interactive masking | MobileSAM | Official lightweight SAM code and checkpoint; mobile-oriented design | Apache-2.0; ONNX/mobile conversion still needs device benchmarking | Strong alternative to EfficientSAM. Compare encoder memory and prompt latency before choosing. |
| P0 | Automatic enhancement | HDRNet | Pretrained models and an Android demo in the official repository | Apache-2.0; old TensorFlow/custom bilateral-slice stack requires modernization | Good architecture for compact global/local automatic correction. Port only after reproducing the bilateral-slice operator with a supported runtime. |
| P1 | Denoise/deblur | NAFNet width-32 variants | Official pretrained restoration checkpoints and code | MIT repository; checkpoint licensing and mobile conversion must be confirmed; not designed as an Android model | Prototype tiled FP16/INT8 conversion. Do not ship until peak memory and seams are controlled. |
| P1 | Reflection removal | ERRNet | Official code and pretrained model | MIT code repository; model is relatively heavy and not mobile-ready | Desktop conversion/quality reference first, then consider a smaller distilled student. |
| P1 | Reflection removal | DExNet | Recent lightweight architecture and open implementation | Apache-2.0 code is promising; verify that an official redistributable checkpoint is actually published | Watch/pilot only after checkpoint provenance is confirmed. |
| Hold | Flare removal | Flare7K / Flare7K++ baseline | Official training/inference project and pretrained assets | NTU S-Lab/non-commercial terms; unsuitable for ordinary commercial app distribution without permission | Useful evaluation reference only. Do not bundle or auto-download under the current license. |
| Hold | Mobile denoise | SplitterNet | Mobile-focused architecture, code and TFLite-oriented workflow | Academic/non-commercial license terms | Useful benchmark/reference, not a distribution candidate without separate permission. |
| Hold | Low-light enhancement | Zero-DCE++ | Very small published architecture and public checkpoints/code | Official project is non-commercial | Architecture reference only unless relicensed or independently trained from compatible data. |
| Hold | Shadow removal | DC-ShadowNet / similar research checkpoints | Public research implementation and weights | Academic/non-commercial restrictions and weak mobile packaging story | Do not integrate directly. Prefer a permissively licensed compact replacement or classical/local-mask workflow. |
| No credible ready model | Dust/spot removal | — | No small, permissively licensed, production-ready detector/remover with clear Android checkpoints was found | Existing examples are often classical, desktop-oriented or incompatibly licensed | Keep manual/assisted spot selection plus bounded inpainting/clone workflow. |
| No clear first choice | General dehaze | AOD-Net, DehazeFormer/gUNet families | Public research implementations and some checkpoints | License/checkpoint/operator/mobile readiness varies and is often unclear | Keep the new classical DCP engines for now; evaluate models offline before choosing one. |

## Official project links

- EfficientSAM: https://github.com/yformer/EfficientSAM
- EfficientSAM ONNX exports: https://huggingface.co/yunyangx/EfficientSAM
- MobileSAM: https://github.com/ChaoningZhang/MobileSAM
- HDRNet: https://github.com/google/hdrnet
- NAFNet: https://github.com/megvii-research/NAFNet
- ERRNet: https://github.com/Vandermode/ERRNet
- DExNet paper: https://arxiv.org/abs/2503.01938
- Flare7K: https://github.com/ykdai/Flare7K
- SplitterNet: https://github.com/rflepp/SplitterNet-Efficient-Mobile-Denoising-Models-CVPR2024

## Mandatory integration gate

1. Record model, code, checkpoint and training-data licenses separately.
2. Pin the exact asset SHA-256, byte size, input/output names, tensor order and normalization.
3. Convert with a reproducible script; never commit an unexplained converted binary.
4. Reject unsupported operators before runtime and report the exact capability reason.
5. Run load to execute in one cancellable transaction and publish actual session lifecycle.
6. Bound encoder/session/output memory in the owner-aware reservation ledger.
7. Measure cold load, warm inference, peak RSS and cancellation latency on representative devices.
8. Test transparent edges, masks, dark scenes, skin, text, screens, LEDs, flare cores and unaffected regions.
9. Keep model-based routes experimental until packaged inference and manual visual validation pass.
10. Preserve a deterministic non-model fallback and never silently export a route different from the visible preview.
