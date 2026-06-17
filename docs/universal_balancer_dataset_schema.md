# Universal Balancer dataset schema

Universal Balancer is a small image-to-edit-parameters model for Kepler Studio.

It predicts the same core values used by `EditParams` so the app can generate a first-pass automatic correction without hardcoding every scene rule.

## Model purpose

```text
input image
→ model
→ EditParams-like float vector
→ native render pipeline
```

The model should not generate pixels. It only predicts correction parameters.

## Recommended first model

- Input size: `224x224` RGB.
- Input range: either `0..1` float or quantized `uint8`, decide during conversion.
- Output: `float[14]`.
- Runtime: TFLite / LiteRT.
- Filename: `app/src/main/assets/models/universal_balancer.tflite`.
- Git policy: model binary remains local and ignored by Git.

## Output vector order

Keep this order stable across dataset, training, conversion, and Android inference:

```text
0  exposure
1  contrast
2  shadows
3  highlights
4  whites
5  blacks
6  temperature
7  tint
8  saturation
9  vibrance
10 clarity
11 dehaze
12 sharpness
13 noiseReduction
```

## JSONL training row

Each row should be one image and its target edit values.

```json
{
  "id": "sample_000001",
  "image_path": "images/sample_000001.jpg",
  "source": "auto_histogram_v1",
  "width": 4080,
  "height": 3060,
  "scene_tags": ["low_light", "portrait"],
  "target": {
    "exposure": 0.12,
    "contrast": 0.08,
    "shadows": 0.20,
    "highlights": -0.05,
    "whites": 0.03,
    "blacks": -0.04,
    "temperature": 0.00,
    "tint": 0.00,
    "saturation": 0.02,
    "vibrance": 0.11,
    "clarity": 0.09,
    "dehaze": 0.04,
    "sharpness": 0.14,
    "noiseReduction": 0.18
  },
  "target_vector": [0.12, 0.08, 0.20, -0.05, 0.03, -0.04, 0.00, 0.00, 0.02, 0.11, 0.09, 0.04, 0.14, 0.18]
}
```

## Label sources

Start with pseudo-labels, then improve.

### v0 labels

Generated from the current histogram auto enhance function.

Pros:

- No human labeling needed.
- Easy to generate many samples.
- Gives the model a stable baseline.

Cons:

- The model can only imitate the current algorithm.
- It will inherit the algorithm's mistakes.

### v1 labels

Human-picked or app-picked better results.

Sources:

- User applies quick auto enhance, then adjusts sliders.
- Save final slider values as improved target.
- Store image + final `EditParams`.

### v2 labels

Teacher model / curated edit references.

Possible sources:

- Higher-quality desktop pipeline.
- Hand-curated presets.
- Before/after pairs converted into approximate parameters.

## Dataset splits

Recommended:

```text
train: 80%
val:   10%
test:  10%
```

Avoid placing near-duplicate images across different splits.

## Normalization

Output values are already mostly in `-1..1` or `0..1` ranges.

Recommended clamping:

```text
exposure:       -1..1
contrast:       -1..1
shadows:        -1..1
highlights:     -1..1
whites:         -1..1
blacks:         -1..1
temperature:    -1..1
tint:           -1..1
saturation:     -1..1
vibrance:       -1..1
clarity:        -1..1
dehaze:         -1..1
sharpness:       0..1
noiseReduction:  0..1
```

## First architecture candidates

Use a tiny mobile backbone:

```text
MobileNetV3-small encoder + global average pooling + dense regression head
EfficientNet-Lite0 encoder + dense regression head
Custom small CNN + dense regression head
```

Loss:

```text
weighted L1 or Huber loss over the 14 outputs
```

Give higher weight to:

```text
exposure
contrast
shadows
highlights
vibrance
clarity
noiseReduction
```

## Android inference contract

The Android side should treat output as a proposal, not a command.

Recommended safety pass:

```text
raw model output
→ clamp each value
→ smooth/scale by strength slider
→ optionally blend with current histogram auto enhance output
→ apply to EditParams
```

## Future app utility

Add a development-only action later:

```text
Export training row
```

It should write:

- source image reference
- current image dimensions
- current `EditParams`
- optional scene tags
- target vector

Do not include private user photos in Git. Keep datasets local or in Kaggle/private storage.
