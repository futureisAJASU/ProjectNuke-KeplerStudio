# Universal Auto Router dataset schema

Universal Auto Router is a lightweight scene/problem classifier for Kepler Studio.

It does not directly edit the image. It decides which correction path or model should be suggested.

## Purpose

```text
input image
→ scene/problem probabilities
→ choose suggested edit pipeline
```

Example:

```text
low_light + flare
→ suggest low-light cleanup and Flare Guard

portrait + backlight
→ protect subject/skin and lift background shadows carefully
```

## Model target

- Input: `224x224` RGB.
- Output: multi-label probabilities.
- Runtime: TFLite / LiteRT.
- Filename: `app/src/main/assets/models/universal_auto_router.tflite`.
- Git policy: model binary remains local and ignored by Git.

## Initial classes

Use multi-label classification, not single-label classification.

```text
normal
low_light
backlight
overexposed
underexposed
portrait
landscape
sky
food
document
flare
reflection
jpeg_artifact
blur
noise
high_dynamic_range
```

A photo can have multiple labels at the same time.

## JSONL training row

```json
{
  "id": "router_000001",
  "image_path": "images/router_000001.jpg",
  "source": "manual_or_rule_v0",
  "width": 4080,
  "height": 3060,
  "labels": {
    "normal": 0,
    "low_light": 1,
    "backlight": 0,
    "overexposed": 0,
    "underexposed": 1,
    "portrait": 1,
    "landscape": 0,
    "sky": 0,
    "food": 0,
    "document": 0,
    "flare": 0,
    "reflection": 0,
    "jpeg_artifact": 0,
    "blur": 0,
    "noise": 1,
    "high_dynamic_range": 0
  },
  "label_vector": [0, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0]
}
```

## v0 rule labels

Before training a model, labels can be generated from existing statistics and metadata.

Possible rule sources:

```text
low_light: mean luminance low
underexposed: median luminance low
overexposed: high percentage of clipped highlights
noise: high ISO if metadata exists, or dark image heuristic
jpeg_artifact: small source file / low quality decode / block artifact estimator
portrait: Edge Masker or face detector later
flare: bright light with halo/streak heuristic later
reflection: manual label first
```

## Android output contract

The app should use router output as a recommendation.

Example routing:

```kotlin
if (lowLight > 0.55f) suggest("Low Light Cleaner")
if (flare > 0.45f) suggest("Flare Guard")
if (portrait > 0.50f) enablePortraitProtection()
if (jpegArtifact > 0.50f) suggest("JPEG Artifact Cleaner")
```

Do not auto-apply destructive or heavy models without user confirmation.

## First implementation without a model

A rule-based router can be added first:

```text
analyze bitmap stats
→ create pseudo probabilities
→ show recommendations
→ collect user choices
→ later train model from those choices
```

This allows the UI and workflow to mature before the model is ready.
