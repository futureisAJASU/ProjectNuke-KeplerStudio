# Flare Guard AI integration path

This document records how to continue from the current rule-based `FlareGuardV0` implementation toward an actual on-device AI model.

The target app asset remains:

```text
app/src/main/assets/models/flare_guard.tflite
```

Model binaries must stay local and must not be committed to Git.

## Current app status

Already implemented in Kepler Studio:

```text
FlareGuardV0.kt
- applyFlareGuardV0(): night/artificial light halo reduction
- applyDaySunFlareGuardV0(): daytime sun/veiling glare reduction
- FlareGuardMode: NightLight / DaySun

AutoRouterV0.kt
- flare
- sun_flare
- veiling_glare
- ghost_blob

Remaster tool development controls
- 장면 분석
- 번짐 완화
- 태양 번짐 완화
- 학습 row 저장
```

The current app path is still rule-based. The AI path below should produce a small student model that works with the existing runtime slot.

## Source A: Flare7K++

### What it is

Flare7K++ is the most immediately usable teacher source for nighttime smartphone flare removal.

Official repo:

```text
https://github.com/ykdai/Flare7K
```

Known details from the official README:

```text
Dataset: Flare7K++
Count: 7,962 flare patterns/images
Composition:
- 7,000 synthetic flares from Flare7K
- 962 real-captured flare images from Flare-R

Flare7K synthetic composition:
- 5,000 scattering flare images
- 2,000 reflective flare images
- 25 scattering flare types
- 10 reflective flare types

Useful extras:
- training code is public
- Flare7K++ checkpoints are public
- test_large.py inference command is documented
- light source annotations are included/updated
```

Important license note:

```text
S-Lab License 1.0
The official README says redistribution and use of the dataset/code for non-commercial purposes should follow this license.
```

For Kepler Studio this means:

```text
Use Flare7K++ as research/teacher/reference.
Do not ship the original PyTorch checkpoint or dataset inside the app.
Train/distill a Kepler-owned mobile student model before app distribution.
```

### Teacher setup

Recommended local/Modal/Kaggle setup:

```bash
git clone https://github.com/ykdai/Flare7K.git
cd Flare7K
pip install -r requirements.txt
python setup.py develop
```

Download the Flare7K++ checkpoint from the official README and place it at:

```text
experiments/flare7kpp/net_g_last.pth
```

Run teacher inference:

```bash
python test_large.py \
  --input dataset/Flare7Kpp/test_data/real/input \
  --output result/test_real/flare7kpp/ \
  --model_path experiments/flare7kpp/net_g_last.pth \
  --flare7kpp
```

### Role in our pipeline

Use Flare7K++ teacher output for:

```text
NightLight flare removal teacher output
night halo/streak/ghost reference results
flare-free target generation
flare residual generation
optional mask generation from input - teacher_output
```

Recommended first student target:

```text
input: 256x256 or 384x384 RGB flare tile
output: 256x256 or 384x384 flare alpha mask
```

Do not start with full clean-image generation on mobile.

Safer app path:

```text
student predicts flare mask
→ Kepler native/Kotlin correction applies conservative local edits
→ preserve actual light source
```

Later target:

```text
input: RGB tile + predicted flare mask
output: correction residual map
```

## Source B: FlareX

### What it is

FlareX is a newer physics-informed lens flare dataset direction that is more relevant to daytime sun flare and general strong-light lens flare than Flare7K++ alone.

Paper:

```text
FlareX: A Physics-Informed Dataset for Lens Flare Removal via 2D Synthesis and 3D Rendering
arXiv: 2510.09995
```

Known paper details:

```text
Problem:
- existing 2D flare synthesis often lacks template diversity
- simple 2D overlay ignores physical principles
- this hurts real-world generalization

Dataset/generation:
- 9,500 2D templates
- templates derived from 95 flare patterns
- 3,000 flare image pairs
- rendered from 60 3D scenes
- mixes 2D synthesis and 3D rendering
```

Current practical note:

```text
At the time of this document, FlareX is useful as a design/data target, but the official code/download path is not as immediately clear as Flare7K++.
Track it, but do not block implementation on it.
```

### Role in our pipeline

Use FlareX for:

```text
DaySun flare synthesis strategy
veiling_glare and sun_flare labels
physics-informed augmentation ideas
3D-rendered flare/reference pairs when available
```

Until a usable FlareX code/data release is confirmed, keep DaySun training bootstrapped by:

```text
rule-based applyDaySunFlareGuardV0()
self-collected bright sun / backlit images
manual before-after correction rows
synthetic sun haze masks
future FlareX data when released/available
```

## Student dataset schema

Use one JSONL row per tile or image.

```json
{
  "id": "flare_student_000001",
  "image_path": "tiles/input/flare_student_000001.png",
  "target_path": "tiles/target/flare_student_000001.png",
  "mask_path": "tiles/mask/flare_student_000001.png",
  "source": "flare7kpp_teacher_v1",
  "mode": "NightLight",
  "width": 384,
  "height": 384,
  "labels": ["flare", "night_light", "ghost_blob"],
  "teacher": {
    "name": "Flare7K++ Uformer",
    "checkpoint": "net_g_last.pth",
    "repo": "ykdai/Flare7K"
  }
}
```

For DaySun:

```json
{
  "id": "flare_student_day_000001",
  "image_path": "tiles/input/flare_student_day_000001.png",
  "target_path": "tiles/target/flare_student_day_000001.png",
  "mask_path": "tiles/mask/flare_student_day_000001.png",
  "source": "kepler_day_sun_rule_v0",
  "mode": "DaySun",
  "width": 384,
  "height": 384,
  "labels": ["sun_flare", "veiling_glare"]
}
```

## Student model targets

### v1: mask student

```text
File: flare_guard.tflite
Input: RGB tile, 256 or 384 square
Output: 1-channel flare alpha mask
Use: preview and export
Runtime: TFLite/LiteRT
Quantization: FP16 first, INT8 later if quality survives
```

App behavior:

```text
model mask
→ blend with rule-based bright-core/haze mask
→ apply conservative correction
```

### v2: residual student

```text
Input: RGB tile + flare mask
Output: RGB residual or corrected RGB tile
Use: export-first, preview optional at lower resolution
```

App behavior:

```text
run only on flare candidate tiles
preserve light source core
fallback to v0 rule-based correction if confidence is low
```

## Training steps

### Stage 0: collect validation samples

Create local validation folders:

```text
datasets/kepler_flare/val/night/input
datasets/kepler_flare/val/day/input
datasets/kepler_flare/val/mixed/input
```

Include:

```text
street lamps
car headlights
signboards
phone lens ghost blobs
sun near frame edge
sun inside frame
backlit landscape
sky washed by sunlight
```

### Stage 1: teacher inference for NightLight

Run Flare7K++ teacher on night samples.

Save:

```text
input image
teacher clean output
residual = input - teacher_output
mask = threshold/blur(abs(residual)) with light-source protection
```

### Stage 2: bootstrap DaySun pseudo targets

Use current rule-based DaySun v0 and manual corrections.

Save:

```text
input image
rule output
pseudo mask from sun/haze detector
manual final edit when available
```

### Stage 3: train mask student

Recommended architecture candidates:

```text
MobileNetV3-small encoder + lightweight decoder
U-Net-lite depthwise separable blocks
Fast-SCNN-like segmentation head
```

Loss:

```text
BCE/Dice for flare mask
optional boundary/soft-mask loss
light-source core protection penalty
```

### Stage 4: export TFLite

Recommended export path:

```text
PyTorch
→ ONNX
→ TensorFlow SavedModel or direct converter path
→ TFLite FP16
→ optional INT8 calibration
```

Target asset:

```text
app/src/main/assets/models/flare_guard.tflite
```

Do not commit the actual `.tflite` file.

## Android integration target

The Android app should support this sequence:

```text
AutoRouterV0 / future Universal Auto Router detects flare mode
→ if flare_guard.tflite exists, load model slot
→ model predicts mask
→ rule/native correction uses mask
→ if model missing or fails, use FlareGuardV0 / DaySunFlareGuardV0
```

## Codex continuation prompt

Use this prompt when starting the next implementation pass:

```text
Read docs/flare_guard_ai_integration.md and implement the next safe step.
Do not add model binaries to Git.
Start by adding an inference wrapper interface for flare_guard.tflite:
- load/unload through existing RemasterModelSession policy
- accept Bitmap tile input
- return a grayscale mask Bitmap or FloatArray
- fallback to rule-based FlareGuardV0 when the model is missing
Do not implement a heavy full-image generative restoration model yet.
Keep first model target as flare mask prediction.
Run .\gradlew.bat assembleDebug and fix compile errors.
```

## Immediate TODO

```text
1. Add FlareGuardModelRunner interface/wrapper.
2. Add tile extraction utility for candidate flare regions.
3. Add model output mask conversion.
4. Blend AI mask with rule-based mask.
5. Keep rule-based fallback.
6. Add local training script outside app module, or document it under tools/ without model binaries.
```
