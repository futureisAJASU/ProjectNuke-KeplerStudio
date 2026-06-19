# Flare Guard mask student trainer

This tool creates the first real `flare_guard.tflite` model for Kepler Studio.

The goal is not final-quality flare removal yet. The first goal is to make the Android app run a real on-device TFLite model that predicts a soft flare mask, then lets the existing Kotlin Flare Guard correction do the conservative edit.

## V0 scope

V0 is only for obvious light-source flare artifacts.

In scope:

```text
street-lamp halo
car-headlight halo
signboard glow
sun flare near the light source
lens ghost blob
light streak
```

Out of scope for V0:

```text
window haze
bus/car-window diffusion
large glass reflection haze
lens fingerprint fog
scene-wide dehaze
ordinary low-contrast photos
```

Do not add broad window-haze samples to the first training set. They need a separate veiling/window-haze channel later, because the correction method is different from point-source flare correction.

## What this trains

```text
Input : RGB image tile, 256x256 or 384x384, float32 in 0..1
Output: 1-channel soft flare alpha mask, same width/height
File  : flare_guard.tflite
```

This is a bootstrap student model. It learns from:

1. pseudo masks generated from bright point-light / halo heuristics similar to `FlareGuardV0`
2. optional synthetic flare blobs and streaks added during training
3. later, real teacher masks from Flare7K++ or manually corrected rows

## Fast path

Create local image folders. The images can be ordinary phone photos. More obvious flare/backlight samples are better.

```text
datasets/kepler_flare/raw/night
datasets/kepler_flare/raw/day
datasets/kepler_flare/raw/mixed
```

Run:

```bash
cd tools/flare_guard_student
python train_mask_student.py \
  --input_glob "../../datasets/kepler_flare/raw/**/*.jpg" \
  --mode mixed \
  --image_size 256 \
  --epochs 12 \
  --batch_size 8 \
  --copy_to_app_assets
```

If your files are PNG:

```bash
python train_mask_student.py --input_glob "../../datasets/kepler_flare/raw/**/*.png" --mode mixed --copy_to_app_assets
```

Output:

```text
runs/flare_guard_student/flare_guard.keras
runs/flare_guard_student/flare_guard_fp16.tflite
../../app/src/main/assets/models/flare_guard.tflite   # only when --copy_to_app_assets is used
```

`app/src/main/assets/models/*.tflite` is ignored by Git, so the generated model stays local.

## Quality expectations

This first model is intentionally a weak student. It will mostly learn where the rule-based flare mask usually appears. That is still useful because it validates:

- Android model loading
- tensor shape compatibility
- model mask output conversion
- fallback behavior when model is missing
- later replacement with a better student trained from Flare7K++ teacher masks

## Recommended dataset count

For a quick smoke test:

```text
30-100 images
```

For a usable first app prototype:

```text
300-1000 mixed images
```

Suggested samples:

```text
street lamps
car headlights
signboards
phone lens ghost blobs
sun near frame edge
sun inside frame
backlit light source with local halo
```

Avoid these for V0:

```text
photos made hazy mainly by a window
large glass reflections without a clear bright source
foggy/low-contrast scenes without flare
lens-smudge haze
```

## Next upgrade path

1. Run Flare7K++ teacher on obvious night/day flare images.
2. Save `mask = blur(threshold(abs(input - teacher_output)))` with bright-core protection.
3. Add those masks as real targets.
4. Train this same model architecture against the teacher masks.
5. Replace only the `.tflite`; Android code can stay the same.
6. Add a separate veiling/window-haze channel after the point-source flare path is stable.
