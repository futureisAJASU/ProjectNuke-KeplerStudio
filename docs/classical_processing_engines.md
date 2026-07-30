# Selectable classical processing engines

Last reviewed: 2026-07-30

KeplerStudio keeps the established V1 path as the default. The engines below are opt-in alternatives selected in Settings and rendered through the same preview/export production path. They do not change the exact V1 golden when their engine IDs are not selected.

## Implemented native engines

| Category | UI engine | Native ID | Implementation | Status |
|---|---|---:|---|---|
| Denoise | Fast edge-aware | 0 | Existing bounded edge-aware filter | Default |
| Denoise | Guided filter | 1 | Existing guided edge-preserving path | Selectable |
| Denoise | Non-local means Lite | 2 | Bounded patch-similarity average over a symmetric local search set, with separate luma/chroma strength and edge protection | Experimental |
| Detail | Masked unsharp | 0 | Existing bounded sharpening | Default |
| Detail | Multiscale Laplacian | 2 | Fine/coarse luma bands with noise, shadow, highlight and delta guards | Experimental |
| Tone | Histogram auto | 0 | Existing neutral/default tone path | Default |
| Tone | CLAHE | 1 | Existing bounded Kotlin CLAHE post-pass | Selectable |
| Tone | Filmic | 2 | Hable-style normalized luma curve with bounded blend | Selectable |
| Tone | Sigmoid | 3 | Normalized logistic luma curve with bounded blend | Selectable |
| Dehaze | Fast contrast | 0 | Existing bounded fast dehaze adjustment | Default |
| Dehaze | Dark-channel prior | 1 | Compact dark-channel transmission estimate with atmospheric-light, transmission, sky and color-delta guards | Experimental |
| Dehaze | Multiscale DCP | 2 | Fine/coarse dark channels combined before bounded recovery | Experimental |

## Design boundaries

- These are compact original implementations informed by published methods; no third-party reference source was copied into the project.
- Non-local means is deliberately bounded rather than exhaustive. It is suitable for direct comparison, not a claim of reference-quality full NLM.
- The multiscale detail engine is a Laplacian-band detail enhancer. It is not labeled as the full Local Laplacian algorithm.
- DCP is a conservative mobile approximation. Bright sky, white objects and low-saturation highlights remain difficult cases, so it stays experimental.
- All new C++ paths preserve alpha, support the native cancellation lease and return identity at zero effective strength where applicable.
- Native scratch admission accounts for the retained source frame and DCP planes. The native implementation rejects scratch plans over 256 MiB.
- Comparison artifacts are bounded to the existing debug comparison resolution and use the existing owner-aware release path.

## Primary references

- Buades, Coll and Morel, *Non-Local Means Denoising*: https://www.ipol.im/pub/art/2011/bcm_nlm/
- Paris et al., *Local Laplacian Filters*: https://people.csail.mit.edu/sparis/publi/2011/siggraph/
- Aubry et al., *Fast Local Laplacian Filters*: https://people.csail.mit.edu/sparis/publi/2014/tog/Aubry_14-Fast_Local_Laplacian_Filters.pdf
- He, Sun and Tang, *Single Image Haze Removal Using Dark Channel Prior*: https://people.csail.mit.edu/kaiming/publications/cvpr09.pdf
- He, Sun and Tang, *Guided Image Filtering*: https://people.csail.mit.edu/kaiming/publications/eccv10guidedfilter.pdf

## Validation still required on devices

- full-resolution latency and peak RSS on representative 8 GiB devices
- cancellation latency during NLM and multiscale DCP
- sky/white-object false restoration for DCP
- chroma stability and texture retention for NLM
- halo/overshoot review for multiscale detail
- preview/export pixel consistency for each engine combination
