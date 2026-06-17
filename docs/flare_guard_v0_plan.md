# Flare Guard v0 plan

Flare Guard should start as a safe, mask-based correction pipeline before becoming a learned restoration model.

## Principle

Do not ask the first version to hallucinate a clean image.

Start with:

```text
flare detection
→ flare mask
→ local correction
→ preserve actual light source
```

This is safer than directly generating corrected pixels.

## v0 target

A rule-based or hybrid flare reducer for preview/export testing.

### Input

- Current preview or export bitmap.
- Optional downscaled analysis bitmap.

### Output

- `flareMask`: grayscale alpha mask.
- Optional corrected bitmap from the native/Kotlin render path.

## Flare types to handle first

```text
strong lamp halo
streetlight glow
phone lens ghost blobs
radial streaks around bright lights
low contrast haze around light sources
```

## What not to do in v0

Avoid:

```text
removing the actual light source
reconstructing hidden texture aggressively
changing faces or important subjects
strongly darkening the whole image
```

## Rule-based v0 pipeline

### 1. Detect bright cores

Find pixels or small regions where luminance is very high.

```text
luma > 0.92
or local percentile top 1 percent
```

### 2. Expand to halo candidate

Create a soft mask around bright cores.

Possible method:

```text
bright core mask
→ blur / dilation
→ subtract core preservation area
→ clamp to halo area
```

### 3. Detect low-frequency haze around lights

Use a blurred luminance image and compare local brightness around light sources.

```text
halo = blurred_luma - local_background_estimate
```

### 4. Correct halo region

Apply conservative local changes only where mask is active:

```text
reduce dehaze-like haze
reduce excessive saturation shift
reduce local brightness bleed
preserve highlight center
```

### 5. Blend safely

Use a strength slider:

```text
strength 0.0..1.0
```

Default should be low, around `0.35`.

## v1 learned mask model

Once v0 works, train a small model to predict flare masks.

```text
input: 256x256 or 384x384 RGB tile
output: 256x256 or 384x384 flare alpha mask
```

This is easier and safer than asking the model to output a fully restored image.

## v2 learned residual model

After mask prediction is stable:

```text
input: RGB tile + flare mask
output: corrected residual or correction map
```

Use tile-based export only for heavy versions.

## Android model slots

```text
flare_guard.tflite
```

Expected modes:

```text
preview: low-res or mask-only
export: tiled correction
```

## UI copy

Use formal app copy:

```text
플레어 완화
강한 광원 주변의 번짐과 흐림을 완화합니다. 광원 자체는 최대한 유지합니다.
```

## Safety fallback

If a model is missing or fails:

```text
fall back to rule-based flare mask
show a non-blocking message
keep the original image unchanged until user applies correction
```
