"""Pure validation helpers for N4 device comparison preflight.

This module contains no torch/numpy dependencies and can be imported
in environments without heavy ML libraries (e.g., for host contract tests).
"""
import hashlib
import json
import os
import sys

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