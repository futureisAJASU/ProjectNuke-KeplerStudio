#!/usr/bin/env python3
"""Train the first Kepler Studio Flare Guard mask student.

This is a bootstrap trainer. It creates pseudo masks from bright-light heuristics
and optional synthetic flare overlays, then exports a small TFLite mask model.

The output model is intended for:
    app/src/main/assets/models/flare_guard.tflite

Do not commit generated model binaries.
"""

from __future__ import annotations

import argparse
import glob
import os
import random
import shutil
from pathlib import Path

import tensorflow as tf

AUTOTUNE = tf.data.AUTOTUNE


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train Flare Guard mask student")
    parser.add_argument("--input_glob", required=True, help="Glob for training images, e.g. datasets/**/*.jpg")
    parser.add_argument("--mode", default="mixed", choices=["night", "day", "mixed"], help="Pseudo-label mode")
    parser.add_argument("--image_size", type=int, default=256, choices=[256, 384], help="Square model input size")
    parser.add_argument("--epochs", type=int, default=12)
    parser.add_argument("--batch_size", type=int, default=8)
    parser.add_argument("--base_channels", type=int, default=16)
    parser.add_argument("--validation_split", type=float, default=0.12)
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--no_synthetic_flare", action="store_true", help="Disable generated flare overlays")
    parser.add_argument("--output_dir", default="runs/flare_guard_student")
    parser.add_argument("--copy_to_app_assets", action="store_true", help="Copy FP16 TFLite to app/src/main/assets/models/flare_guard.tflite")
    return parser.parse_args()


def list_images(pattern: str, seed: int) -> list[str]:
    paths = sorted(glob.glob(pattern, recursive=True))
    paths = [p for p in paths if Path(p).suffix.lower() in {".jpg", ".jpeg", ".png", ".webp", ".bmp"}]
    random.Random(seed).shuffle(paths)
    return paths


def load_image(path: tf.Tensor, image_size: int) -> tf.Tensor:
    raw = tf.io.read_file(path)
    image = tf.io.decode_image(raw, channels=3, expand_animations=False)
    image.set_shape([None, None, 3])
    image = tf.image.convert_image_dtype(image, tf.float32)
    image = tf.image.resize(image, [image_size, image_size], method=tf.image.ResizeMethod.BILINEAR)
    return tf.clip_by_value(image, 0.0, 1.0)


def luma(image: tf.Tensor) -> tf.Tensor:
    r, g, b = tf.split(image, 3, axis=-1)
    return tf.clip_by_value(0.2126 * r + 0.7152 * g + 0.0722 * b, 0.0, 1.0)


def box_blur(mask: tf.Tensor, kernel: int, passes: int) -> tf.Tensor:
    x = tf.expand_dims(mask, axis=0)
    for _ in range(passes):
        x = tf.nn.avg_pool2d(x, ksize=kernel, strides=1, padding="SAME")
    return tf.squeeze(x, axis=0)


def pseudo_flare_mask(image: tf.Tensor, mode: str) -> tf.Tensor:
    y = luma(image)
    max_c = tf.reduce_max(image, axis=-1, keepdims=True)
    min_c = tf.reduce_min(image, axis=-1, keepdims=True)
    chroma = tf.clip_by_value(max_c - min_c, 0.0, 1.0)

    if mode == "night":
        threshold = 0.90
        blur_kernel = 17
    elif mode == "day":
        threshold = 0.86
        blur_kernel = 31
    else:
        mean_luma = tf.reduce_mean(y)
        threshold = tf.where(mean_luma > 0.50, 0.86, 0.90)
        blur_kernel = 25

    bright = tf.clip_by_value((y - threshold) / tf.maximum(1.0 - threshold, 1e-4), 0.0, 1.0)
    color_boost = tf.clip_by_value((chroma - 0.07) / 0.25, 0.0, 1.0)
    mask = tf.maximum(bright, bright * (0.55 + 0.45 * color_boost))
    mask = tf.nn.max_pool2d(tf.expand_dims(mask, 0), ksize=7, strides=1, padding="SAME")[0]
    mask = box_blur(mask, blur_kernel, passes=2)
    return tf.clip_by_value(mask, 0.0, 1.0)


def synthetic_flare(image: tf.Tensor, image_size: int) -> tuple[tf.Tensor, tf.Tensor]:
    h = image_size
    w = image_size
    xs = tf.linspace(0.0, 1.0, w)
    ys = tf.linspace(0.0, 1.0, h)
    yy, xx = tf.meshgrid(ys, xs, indexing="ij")
    xx = tf.expand_dims(xx, axis=-1)
    yy = tf.expand_dims(yy, axis=-1)

    cx = tf.random.uniform([], -0.10, 1.10)
    cy = tf.random.uniform([], -0.10, 1.10)
    sigma = tf.random.uniform([], 0.025, 0.12)
    strength = tf.random.uniform([], 0.35, 0.95)
    angle = tf.random.uniform([], 0.0, 6.2831853)
    streak_width = tf.random.uniform([], 0.006, 0.025)
    streak_length = tf.random.uniform([], 0.18, 0.70)

    dx = xx - cx
    dy = yy - cy
    dist2 = dx * dx + dy * dy
    blob = tf.exp(-dist2 / (2.0 * sigma * sigma))

    along = tf.cos(angle) * dx + tf.sin(angle) * dy
    across = -tf.sin(angle) * dx + tf.cos(angle) * dy
    streak = tf.exp(-(across * across) / (2.0 * streak_width * streak_width))
    streak *= tf.exp(-(along * along) / (2.0 * streak_length * streak_length))

    ghost_scale = tf.random.uniform([], 0.35, 0.75)
    gx = 1.0 - cx + tf.random.uniform([], -0.08, 0.08)
    gy = 1.0 - cy + tf.random.uniform([], -0.08, 0.08)
    ghost = tf.exp(-(((xx - gx) ** 2 + (yy - gy) ** 2) / (2.0 * (sigma * 0.85) ** 2))) * ghost_scale

    mask = tf.clip_by_value(tf.maximum(tf.maximum(blob, streak * 0.70), ghost) * strength, 0.0, 1.0)
    mask = box_blur(mask, 9, passes=1)

    warm = tf.stack([
        tf.ones([], dtype=tf.float32),
        tf.random.uniform([], 0.72, 0.98),
        tf.random.uniform([], 0.42, 0.82),
    ])
    cool = tf.stack([
        tf.random.uniform([], 0.65, 0.95),
        tf.random.uniform([], 0.75, 1.00),
        tf.ones([], dtype=tf.float32),
    ])
    color = tf.cond(tf.random.uniform([]) > 0.28, lambda: warm, lambda: cool)
    color = tf.reshape(color, [1, 1, 3])

    flare_rgb = mask * color
    out = tf.clip_by_value(image + flare_rgb * tf.random.uniform([], 0.28, 0.75), 0.0, 1.0)
    return out, mask


def augment_and_label(path: tf.Tensor, image_size: int, mode: str, use_synthetic: bool) -> tuple[tf.Tensor, tf.Tensor]:
    image = load_image(path, image_size)
    image = tf.image.random_flip_left_right(image)
    image = tf.image.random_flip_up_down(image)
    image = tf.image.random_brightness(image, max_delta=0.06)
    image = tf.image.random_contrast(image, lower=0.88, upper=1.12)
    image = tf.clip_by_value(image, 0.0, 1.0)

    base_mask = pseudo_flare_mask(image, mode)
    if use_synthetic:
        should_add = tf.random.uniform([]) > 0.22

        def add() -> tuple[tf.Tensor, tf.Tensor]:
            augmented, synthetic_mask = synthetic_flare(image, image_size)
            combined = tf.maximum(pseudo_flare_mask(augmented, mode), synthetic_mask)
            return augmented, tf.clip_by_value(combined, 0.0, 1.0)

        def keep() -> tuple[tf.Tensor, tf.Tensor]:
            return image, base_mask

        image, mask = tf.cond(should_add, add, keep)
    else:
        mask = base_mask

    return tf.clip_by_value(image, 0.0, 1.0), tf.clip_by_value(mask, 0.0, 1.0)


def make_dataset(paths: list[str], args: argparse.Namespace, training: bool) -> tf.data.Dataset:
    ds = tf.data.Dataset.from_tensor_slices(paths)
    if training:
        ds = ds.shuffle(min(len(paths), 2048), seed=args.seed, reshuffle_each_iteration=True)
    ds = ds.map(
        lambda p: augment_and_label(p, args.image_size, args.mode, not args.no_synthetic_flare),
        num_parallel_calls=AUTOTUNE,
    )
    ds = ds.batch(args.batch_size).prefetch(AUTOTUNE)
    return ds


def sep_block(x: tf.Tensor, channels: int, stride: int = 1, name: str = "block") -> tf.Tensor:
    x = tf.keras.layers.SeparableConv2D(channels, 3, strides=stride, padding="same", use_bias=False, name=f"{name}_sep")(x)
    x = tf.keras.layers.BatchNormalization(name=f"{name}_bn")(x)
    x = tf.keras.layers.Activation("relu", name=f"{name}_relu")(x)
    return x


def build_model(image_size: int, base_channels: int) -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(image_size, image_size, 3), name="rgb")
    x1 = sep_block(inputs, base_channels, name="enc1a")
    x1 = sep_block(x1, base_channels, name="enc1b")

    x2 = sep_block(x1, base_channels * 2, stride=2, name="enc2a")
    x2 = sep_block(x2, base_channels * 2, name="enc2b")

    x3 = sep_block(x2, base_channels * 4, stride=2, name="enc3a")
    x3 = sep_block(x3, base_channels * 4, name="enc3b")

    x4 = sep_block(x3, base_channels * 6, stride=2, name="enc4a")
    x4 = sep_block(x4, base_channels * 6, name="enc4b")

    y = tf.keras.layers.UpSampling2D(size=2, interpolation="bilinear", name="up3")(x4)
    y = tf.keras.layers.Concatenate(name="skip3")([y, x3])
    y = sep_block(y, base_channels * 4, name="dec3")

    y = tf.keras.layers.UpSampling2D(size=2, interpolation="bilinear", name="up2")(y)
    y = tf.keras.layers.Concatenate(name="skip2")([y, x2])
    y = sep_block(y, base_channels * 2, name="dec2")

    y = tf.keras.layers.UpSampling2D(size=2, interpolation="bilinear", name="up1")(y)
    y = tf.keras.layers.Concatenate(name="skip1")([y, x1])
    y = sep_block(y, base_channels, name="dec1")

    outputs = tf.keras.layers.Conv2D(1, 1, activation="sigmoid", name="flare_mask")(y)
    return tf.keras.Model(inputs=inputs, outputs=outputs, name="kepler_flare_guard_student")


def dice_loss(y_true: tf.Tensor, y_pred: tf.Tensor) -> tf.Tensor:
    y_true = tf.cast(y_true, tf.float32)
    y_pred = tf.cast(y_pred, tf.float32)
    intersection = tf.reduce_sum(y_true * y_pred, axis=[1, 2, 3])
    denom = tf.reduce_sum(y_true + y_pred, axis=[1, 2, 3])
    dice = (2.0 * intersection + 1.0) / (denom + 1.0)
    return 1.0 - tf.reduce_mean(dice)


def total_loss(y_true: tf.Tensor, y_pred: tf.Tensor) -> tf.Tensor:
    bce = tf.keras.losses.binary_crossentropy(y_true, y_pred)
    bce = tf.reduce_mean(bce)
    return bce + 0.65 * dice_loss(y_true, y_pred)


def export_tflite(model: tf.keras.Model, output_dir: Path) -> Path:
    saved_model_path = output_dir / "saved_model"
    fp16_path = output_dir / "flare_guard_fp16.tflite"
    model.export(str(saved_model_path))

    converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_path))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite_model = converter.convert()
    fp16_path.write_bytes(tflite_model)
    return fp16_path


def main() -> None:
    args = parse_args()
    random.seed(args.seed)
    tf.random.set_seed(args.seed)

    paths = list_images(args.input_glob, args.seed)
    if len(paths) < 4:
        raise SystemExit(f"Need at least 4 images, found {len(paths)} from: {args.input_glob}")

    val_count = max(1, int(len(paths) * args.validation_split))
    val_paths = paths[:val_count]
    train_paths = paths[val_count:]
    if not train_paths:
        train_paths = paths
        val_paths = paths[:1]

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Training images: {len(train_paths)}")
    print(f"Validation images: {len(val_paths)}")
    print(f"Mode: {args.mode}, image_size: {args.image_size}")
    print(f"Output dir: {output_dir}")

    train_ds = make_dataset(train_paths, args, training=True)
    val_ds = make_dataset(val_paths, args, training=False)

    model = build_model(args.image_size, args.base_channels)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=2e-3),
        loss=total_loss,
        metrics=[tf.keras.metrics.BinaryIoU(target_class_ids=[1], threshold=0.5, name="mask_iou")],
    )
    model.summary()

    callbacks = [
        tf.keras.callbacks.ModelCheckpoint(
            filepath=str(output_dir / "flare_guard.keras"),
            monitor="val_loss",
            save_best_only=True,
        ),
        tf.keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=2, min_lr=2e-5),
    ]

    model.fit(train_ds, validation_data=val_ds, epochs=args.epochs, callbacks=callbacks)

    best_model_path = output_dir / "flare_guard.keras"
    if best_model_path.exists():
        model = tf.keras.models.load_model(
            best_model_path,
            custom_objects={"total_loss": total_loss, "dice_loss": dice_loss},
        )

    tflite_path = export_tflite(model, output_dir)
    print(f"Wrote: {tflite_path}")

    if args.copy_to_app_assets:
        repo_root = Path(__file__).resolve().parents[2]
        asset_dir = repo_root / "app" / "src" / "main" / "assets" / "models"
        asset_dir.mkdir(parents=True, exist_ok=True)
        target = asset_dir / "flare_guard.tflite"
        shutil.copyfile(tflite_path, target)
        print(f"Copied local model asset: {target}")
        print("Do not commit this .tflite file; it is ignored by Git.")


if __name__ == "__main__":
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
    main()
