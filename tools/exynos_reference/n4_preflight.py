"""Pure validation helpers for N4 device comparison preflight.

This module has no torch dependency and is the single source of truth for
correctness-critical N4 validation. ``compare_n4.py`` (production) and the host
contract tests both import these helpers so production cannot diverge from the
tested behavior. Only ``numpy`` (a lightweight host dependency) is used by the
``.f32le`` loader functions; the remaining validators are pure Python.
"""
import hashlib
import json
import os
import sys

import numpy as np

THIS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, THIS_DIR)
from checkpoint_identity import (  # noqa: E402
    GENERAL_CHECKPOINT_SHA256,
    WEIGHTS_IDENTITY_GENERAL,
)

HALO = 34
TILE = 128
SCALE = 4
C = 3

INPUT_TILE_BYTES = C * TILE * TILE * 4        # 3*128*128*4 = 196608
OUTPUT_TILE_BYTES = C * (TILE * SCALE) * (TILE * SCALE) * 4  # 3*512*512*4 = 3145728

DECOMPOSITION_FIXTURES = {
    "seam_stress_188x188": 4,
    "smooth_257x257": 16,
}


def full_reference_expected_bytes(w, h):
    """Expected byte size of a full-reference .f32le tensor (C, h*SCALE, w*SCALE)."""
    return C * h * SCALE * w * SCALE * 4


def full_reference_expected_shape(w, h):
    """Expected 3-D CHW shape of a full-reference .f32le tensor."""
    return (C, h * SCALE, w * SCALE)


def validate_reference_identity(manifest):
    """Validate N4 reference manifest identity for device comparison.

    Args:
        manifest: dict loaded from n4_reference_manifest.json

    Returns:
        tuple (bool, str): (is_valid, error_message)
    """
    identity = manifest.get("weights_identity")
    if identity != WEIGHTS_IDENTITY_GENERAL:
        return False, (
            f"weights_identity is {identity!r}, not {WEIGHTS_IDENTITY_GENERAL!r}"
        )

    general_sha = manifest.get("general_checkpoint_sha256")
    if general_sha != GENERAL_CHECKPOINT_SHA256:
        return False, (
            f"general_checkpoint_sha256 {general_sha!r} != pinned {GENERAL_CHECKPOINT_SHA256!r}"
        )

    checkpoint_sha = manifest.get("checkpoint_sha256")
    if checkpoint_sha is None:
        return False, "checkpoint_sha256 is null"
    if checkpoint_sha != GENERAL_CHECKPOINT_SHA256:
        return False, (
            f"checkpoint_sha256 {checkpoint_sha!r} != pinned {GENERAL_CHECKPOINT_SHA256!r}"
        )

    fixtures = manifest.get("fixtures", {})
    for name, info in fixtures.items():
        if "full_raw_sha256" not in info:
            return False, f"fixture {name!r} missing full_raw_sha256"

    return True, ""


def load_reference_identity(ref_dir):
    """Read and validate the N4 reference manifest, failing closed.

    Returns the manifest dict or raises SystemExit if the manifest is absent or
    its identity does not match the pinned GENERAL checkpoint.
    """
    manifest_path = os.path.join(ref_dir, "n4_reference_manifest.json")
    if not os.path.exists(manifest_path):
        raise SystemExit(
            f"reference manifest not found at {manifest_path}; run n4_reference.py "
            f"--weights-dir <dir> to generate a GENERAL reference first"
        )
    manifest = json.load(open(manifest_path))
    ok, err = validate_reference_identity(manifest)
    if not ok:
        raise SystemExit(
            f"refusing N4 device comparison: {err}. Regenerate with "
            f"n4_reference.py --weights-dir <dir> using the pinned GENERAL checkpoint."
        )
    return manifest


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def validate_full_reference_tensor(ref_path, expected_sha, fixture_name):
    """Validate a single full reference .f32le tensor against manifest SHA.

    Args:
        ref_path: path to .f32le file
        expected_sha: expected SHA-256 from manifest
        fixture_name: name for error messages

    Returns:
        tuple (bool, str): (is_valid, error_message)
    """
    if not os.path.exists(ref_path):
        return False, f"reference tensor not found at {ref_path}"
    actual = sha256_file(ref_path)
    if actual != expected_sha:
        return False, f"fixture {fixture_name!r} SHA mismatch: manifest={expected_sha} actual={actual}"
    return True, ""


def load_full_reference_f32le(ref_path, expected_sha, fixture_name, w, h):
    """Load a full-reference .f32le tensor and return a 3-D CHW float32 array.

    Fails closed (SystemExit) with fixture name, path, and actual-vs-expected
    bytes/elements on any SHA, byte-count, or element-count mismatch. Never
    relies on ``assert``.

    Returns:
        numpy.ndarray of shape (C, h * SCALE, w * SCALE), dtype float32.
    """
    if not os.path.exists(ref_path):
        raise SystemExit(
            f"full reference {fixture_name!r}: file not found at {ref_path}"
        )
    with open(ref_path, "rb") as f:
        data = f.read()

    actual = sha256_bytes(data)
    if actual != expected_sha:
        raise SystemExit(
            f"full reference {fixture_name!r}: SHA-256 mismatch "
            f"manifest={expected_sha} actual={actual} path={ref_path}"
        )

    expected_elements = C * h * SCALE * w * SCALE
    expected_bytes = expected_elements * 4
    if len(data) != expected_bytes:
        raise SystemExit(
            f"full reference {fixture_name!r}: byte count {len(data)} != "
            f"expected {expected_bytes} path={ref_path}"
        )

    arr = np.frombuffer(data, dtype="<f4")
    if arr.size != expected_elements:
        raise SystemExit(
            f"full reference {fixture_name!r}: element count {arr.size} != "
            f"expected {expected_elements} path={ref_path}"
        )

    full = arr.copy()
    return full.reshape(C, h * SCALE, w * SCALE)


def validate_assembled_device_output(device_dir, name, w, h):
    """Validate canonical assembled device output existence and exact byte size.

    Args:
        device_dir: path to the device output directory
        name: fixture name
        w, h: fixture dimensions

    Returns:
        tuple (bool, str): (is_valid, error_message)
    """
    path = os.path.join(device_dir, f"assembled_{name}.f32le")
    if not os.path.exists(path):
        return False, f"assembled device output missing for fixture {name!r} at {path}"
    expected = full_reference_expected_bytes(w, h)
    size = os.path.getsize(path)
    if size != expected:
        return False, (
            f"assembled device output for {name!r} has size {size} bytes, "
            f"expected {expected} (path={path})"
        )
    return True, ""


def require_assembled_device_output(device_dir, name, w, h):
    """Fail closed if the canonical assembled device output is absent or wrong-sized."""
    ok, err = validate_assembled_device_output(device_dir, name, w, h)
    if not ok:
        raise SystemExit(f"FAIL: {err}")
    return None


def plan_axis(dim, halo=HALO):
    """TilePlanner-equivalent axis planning (pure Python)."""
    step = TILE - 2 * halo
    if step < 1:
        raise ValueError("halo too large")
    starts = []
    pos = 0
    while True:
        starts.append(pos)
        if pos >= dim - TILE:
            break
        nxt = pos + step
        pos = nxt if nxt >= dim - TILE else nxt
        pos = dim - TILE if pos > dim - TILE else pos
    starts = sorted(set(starts))
    edges = [0]
    for t in range(len(starts) - 1):
        left_valid_next = 0 if starts[t + 1] == 0 else starts[t + 1] + halo
        right_valid_cur = dim if starts[t] + TILE == dim else starts[t] + TILE - halo
        edges.append((left_valid_next + right_valid_cur) * 2)
    edges.append(dim * SCALE)
    return starts, edges


def expected_tile_indices(w, h):
    """Compute TilePlanner-equivalent tile grid for fixture."""
    sx, _ = plan_axis(w)
    sy, _ = plan_axis(h)
    return len(sx), len(sy), len(sx) * len(sy)


def validate_decomposition_artifacts(device_dir, name, w, h):
    """Validate all decomposition artifacts for a designated fixture.

    Args:
        device_dir: path to device output directory
        name: fixture name (must be in DECOMPOSITION_FIXTURES)
        w, h: fixture dimensions

    Returns:
        tuple (bool, str): (is_valid, error_message)
    """
    if name not in DECOMPOSITION_FIXTURES:
        return True, ""  # non-designated: optional

    expected = DECOMPOSITION_FIXTURES[name]
    tiles_dir = os.path.join(device_dir, "tiles", name)
    if not os.path.isdir(tiles_dir):
        return False, f"tiles directory missing at {tiles_dir}"

    files = os.listdir(tiles_dir)
    input_files = sorted(f for f in files if f.endswith("_input.f32le"))
    output_files = sorted(f for f in files if f.endswith("_output.f32le"))

    if len(input_files) != expected:
        return False, f"expected {expected} input tiles, found {len(input_files)}: {input_files}"
    if len(output_files) != expected:
        return False, f"expected {expected} output tiles, found {len(output_files)}: {output_files}"

    indices = []
    for f in input_files:
        try:
            idx = int(f[len("tile_"):-len("_input.f32le")])
        except ValueError:
            return False, f"invalid input tile filename {f!r}"
        indices.append(idx)

    expected_indices = set(range(expected))
    actual_indices = set(indices)
    if actual_indices != expected_indices:
        return False, f"tile indices {sorted(actual_indices)} != expected {sorted(expected_indices)}"
    if len(indices) != len(actual_indices):
        return False, f"duplicate tile indices: {indices}"

    for idx in range(expected):
        inp_path = os.path.join(tiles_dir, f"tile_{idx}_input.f32le")
        out_path = os.path.join(tiles_dir, f"tile_{idx}_output.f32le")
        if not os.path.exists(inp_path):
            return False, f"missing input tile {idx} at {inp_path}"
        if not os.path.exists(out_path):
            return False, f"missing output tile {idx} at {out_path}"
        inp_size = os.path.getsize(inp_path)
        out_size = os.path.getsize(out_path)
        if inp_size != INPUT_TILE_BYTES:
            return False, f"tile {idx} input size {inp_size} != {INPUT_TILE_BYTES}"
        if out_size != OUTPUT_TILE_BYTES:
            return False, f"tile {idx} output size {out_size} != {OUTPUT_TILE_BYTES}"

    return True, ""


def require_decomposition_artifacts(device_dir, name, w, h):
    """Fail closed if a designated decomposition fixture's artifacts are invalid."""
    ok, err = validate_decomposition_artifacts(device_dir, name, w, h)
    if not ok:
        raise SystemExit(f"decomposition fixture {name!r}: {err}")
    return None


def validate_canonical_fixture_count(manifest):
    """Assert canonical fixtures manifest has exactly 25 fixtures."""
    fixtures = manifest.get("fixtures", [])
    if len(fixtures) != 25:
        return False, f"canonical fixture count {len(fixtures)} != 25"
    return True, ""


def validate_planned_tile_count(manifest):
    """Assert total planned tiles across all fixtures equals 280."""
    total = 0
    for f in manifest.get("fixtures", []):
        w, h = f["width"], f["height"]
        _, _, count = expected_tile_indices(w, h)
        total += count
    if total != 280:
        return False, f"planned tile total {total} != 280"
    return True, ""


def require_canonical_corpus(manifest):
    """Fail closed if the canonical fixtures manifest is not 25 fixtures / 280 tiles."""
    ok, err = validate_canonical_fixture_count(manifest)
    if not ok:
        raise SystemExit(err)
    ok, err = validate_planned_tile_count(manifest)
    if not ok:
        raise SystemExit(err)
    return None