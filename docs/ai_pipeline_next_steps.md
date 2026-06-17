# Kepler Studio AI pipeline next steps

## Current status

Kepler Studio already has the first AI infrastructure pieces:

- On-device model catalog and model slots.
- Single active model session policy.
- MediaPipe Image Segmenter load path for `edge_masker.task`.
- TFLite Interpreter load path for selected `.tflite` slots.
- Mask-aware remaster prototype.
- Editable selection/mask layer data model.
- Brush-based mask editing prototype.

The next work should turn these pieces into a reliable editor workflow.

## Priority 1: Make Edge Masker visible and debuggable

### Goal

The user must be able to see what the model selected before trusting the result.

### Tasks

1. Add a preview overlay for the active selection layer.
2. Use a translucent overlay color on top of the photo preview.
3. Add a small badge showing the active mask name.
4. Add a toggle: `마스크 표시`.
5. Add optional raw category debug mode for `edge_masker`.

### Notes

The first implementation can show the selected mask as a simple red or blue alpha overlay. It does not need a perfect Lightroom-style mask preview yet.

## Priority 2: Make automatic masks become editable layers

### Goal

An AI-generated mask should never be one-shot only. It should become a reusable `SelectionLayer`.

### Required flow

```text
Edge Masker 로드
→ 피사체 가져오기
→ SelectionLayer(kind=Subject) 생성
→ 사용자가 브러시로 더하기/빼기
→ 선택 마스크 보정 적용
```

### Extra layers

Add helper actions:

- `배경 만들기`: active subject mask copied with `inverted=true` or a new background mask layer.
- `복제`: duplicate active mask as a new layer.
- `이름 변경`: optional later.

## Priority 3: Apply local selection edits to export

### Current issue

The preview can apply a local masked edit, but export still needs a final combined render path.

### Target export pipeline

```text
source bitmap
→ global EditParams render
→ for each enabled selection layer:
    → render localParams merged with global params
    → blend by layer bitmap / inverted / opacity
→ encode as selected format
```

### Important

Do not mutate or destroy the layer bitmaps while exporting. Use scaled copies when needed.

## Priority 4: Universal Balancer dataset

### Goal

Create a small mobile model that predicts edit parameters from an image.

### First model target

- Input: 224x224 RGB image.
- Output: 14 float values matching `EditParams`.
- Runtime: TFLite / LiteRT.
- Initial labels: generated from the existing histogram auto enhance function.

### Output order

```text
exposure
contrast
shadows
highlights
whites
blacks
temperature
tint
saturation
vibrance
clarity
dehaze
sharpness
noiseReduction
```

## Priority 5: Auto Router dataset

### Goal

Classify which helper path should be suggested.

### Initial labels

```text
normal
low_light
backlight
overexposed
underexposed
portrait
landscape
flare
reflection
jpeg_artifact
document
food
```

This can start rule-based and later become a model.

## Priority 6: Flare Guard first version

Do not start with a full restoration model.

Start with:

```text
bright light / halo / streak detector
→ flare mask
→ reduce local haze, saturation, and over-bright bleed
→ preserve the actual light source
```

Then replace the rule-based mask with a learned model later.

## Codex task order

1. Restore classic V2 editor UI while keeping export settings dialog.
2. Add preview overlay for active mask.
3. Move `MaskingToolPanel()` into the actual `마스크` tool tab.
4. Add subject-to-background mask helper.
5. Apply selection local edits during export.
6. Add Universal Balancer dataset export command / utility.
