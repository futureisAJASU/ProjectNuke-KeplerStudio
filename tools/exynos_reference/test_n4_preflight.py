#!/usr/bin/env python3
"""Deterministic host contract tests for N4 comparison preflight.

Tests negative cases A-E as specified in the N4 corrective:
A. geometry-only manifest -> rejected
B. weights_identity GENERAL + pinned general_checkpoint_sha256 but checkpoint_sha256 missing/wrong -> rejected
C. valid GENERAL identity but one full reference tensor hash does not match manifest -> rejected
D. one canonical assembled device fixture missing -> rejected
E. one designated decomposition output tile missing -> rejected

Uses small synthetic files, no real S24 required.
"""
import json
import os
import sys
import tempfile
import unittest

import numpy as np

THIS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, THIS_DIR)

from n4_preflight import (
    validate_reference_identity,
    validate_full_reference_tensor,
    load_full_reference_f32le,
    validate_decomposition_artifacts,
    require_decomposition_artifacts,
    require_assembled_device_output,
    validate_canonical_fixture_count,
    validate_planned_tile_count,
    sha256_bytes,
    full_reference_expected_bytes,
    DECOMPOSITION_FIXTURES,
    C,
    SCALE,
    INPUT_TILE_BYTES,
    OUTPUT_TILE_BYTES,
)


def make_baseline_manifest():
    """Create a minimal valid GENERAL manifest structure."""
    return {
        "weights_identity": "GENERAL",
        "general_checkpoint_sha256": "8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292",
        "checkpoint_sha256": "8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292",
        "fixtures": {
            "test_fixture": {
                "width": 188,
                "height": 188,
                "full_raw_sha256": "a" * 64,
            }
        }
    }


def make_canonical_fixtures_manifest():
    """Create a minimal canonical fixtures manifest with 25 fixtures."""
    # Only need 25 fixture entries with width/height to test cardinality
    fixtures = []
    # 5 groups x 5 content types
    groups = [
        (188, 188),  # 4 tiles each -> 20
        (257, 191),  # 12 tiles each -> 60
        (191, 257),  # 12 tiles each -> 60
        (257, 257),  # 16 tiles each -> 80
        (301, 227),  # 12 tiles each -> 60
    ]
    contents = ["smooth", "seam_stress", "high_frequency", "noise", "mixed"]
    for (w, h) in groups:
        for c in contents:
            fixtures.append({"name": f"{c}_{w}x{h}", "width": w, "height": h})
    return {"fixtures": fixtures}


class TestPreflightA(unittest.TestCase):
    """A. geometry-only manifest -> rejected"""

    def test_geometry_only_weights_identity(self):
        manifest = make_baseline_manifest()
        manifest["weights_identity"] = "deterministic-seeded-geometry-only"
        ok, err = validate_reference_identity(manifest)
        self.assertFalse(ok)
        self.assertIn("weights_identity", err)
        self.assertIn("deterministic-seeded-geometry-only", err)

    def test_geometry_only_missing_checkpoint_sha(self):
        manifest = make_baseline_manifest()
        manifest["weights_identity"] = "deterministic-seeded-geometry-only"
        manifest["checkpoint_sha256"] = None
        ok, err = validate_reference_identity(manifest)
        self.assertFalse(ok)


class TestPreflightB(unittest.TestCase):
    """B. weights_identity GENERAL + pinned general_checkpoint_sha256 but checkpoint_sha256 missing/wrong -> rejected"""

    def test_checkpoint_sha256_missing(self):
        manifest = make_baseline_manifest()
        manifest["checkpoint_sha256"] = None
        ok, err = validate_reference_identity(manifest)
        self.assertFalse(ok)
        self.assertIn("null", err.lower())

    def test_checkpoint_sha256_wrong(self):
        manifest = make_baseline_manifest()
        manifest["checkpoint_sha256"] = "b" * 64
        ok, err = validate_reference_identity(manifest)
        self.assertFalse(ok)
        self.assertIn("checkpoint_sha256", err)


class TestPreflightC(unittest.TestCase):
    """C. valid GENERAL identity but one full reference tensor hash does not match manifest -> rejected"""

    def test_tensor_sha_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            ref_path = os.path.join(tmp, "test_fixture_raw.f32le")
            # Write some dummy data
            with open(ref_path, "wb") as f:
                f.write(b"dummy data")
            # Manifest expects different SHA
            manifest = make_baseline_manifest()
            manifest["fixtures"]["test_fixture"]["full_raw_sha256"] = "a" * 64
            ok, err = validate_full_reference_tensor(ref_path, "a" * 64, "test_fixture")
            self.assertFalse(ok)
            self.assertIn("SHA mismatch", err)


class TestPreflightD(unittest.TestCase):
    """D. one canonical assembled device fixture missing -> rejected"""

    def _write_assembled(self, device_dir, name, w, h):
        """Write an assembled device output with the exact canonical byte size."""
        path = os.path.join(device_dir, f"assembled_{name}.f32le")
        size = full_reference_expected_bytes(w, h)
        with open(path, "wb") as fh:
            fh.seek(size - 1)
            fh.write(b"\x00")
        return path

    def test_missing_assembled_device_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            device_dir = os.path.join(tmp, "device")
            os.makedirs(device_dir)
            manifest = make_canonical_fixtures_manifest()
            fixtures = manifest["fixtures"]
            self.assertEqual(len(fixtures), 25)

            # 24 canonical fixtures present, 1 intentionally absent.
            for f in fixtures[:24]:
                self._write_assembled(device_dir, f["name"], f["width"], f["height"])

            missing = fixtures[24]
            missing_path = os.path.join(device_dir, f"assembled_{missing['name']}.f32le")
            self.assertFalse(os.path.exists(missing_path))

            # Present fixtures pass the shared production validator.
            for f in fixtures[:24]:
                require_assembled_device_output(device_dir, f["name"], f["width"], f["height"])

            # The absent fixture must be rejected with exact fixture/path.
            with self.assertRaises(SystemExit) as cm:
                require_assembled_device_output(device_dir, missing["name"], missing["width"], missing["height"])
            msg = str(cm.exception)
            self.assertIn(missing["name"], msg)
            self.assertIn(f"assembled_{missing['name']}.f32le", msg)
            self.assertIn(missing_path, msg)

    def test_wrong_assembled_device_output_size(self):
        with tempfile.TemporaryDirectory() as tmp:
            device_dir = os.path.join(tmp, "device")
            os.makedirs(device_dir)
            name, w, h = "seam_stress_188x188", 188, 188
            path = os.path.join(device_dir, f"assembled_{name}.f32le")
            with open(path, "wb") as fh:
                fh.write(b"\x00" * (full_reference_expected_bytes(w, h) - 16))
            with self.assertRaises(SystemExit) as cm:
                require_assembled_device_output(device_dir, name, w, h)
            msg = str(cm.exception)
            self.assertIn(name, msg)
            self.assertIn("size", msg)


class TestDecompositionCardinality(unittest.TestCase):
    """Lock designated decomposition fixture cardinality."""

    def test_designated_fixture_tile_counts(self):
        self.assertEqual(DECOMPOSITION_FIXTURES["seam_stress_188x188"], 4)
        self.assertEqual(DECOMPOSITION_FIXTURES["smooth_257x257"], 16)


class TestProductionF32leLoader(unittest.TestCase):
    """Regression: production full-reference .f32le must load as 3-D CHW."""

    SYNTH_W = 3
    SYNTH_H = 2

    def _synthetic(self):
        shape = (C, self.SYNTH_H * SCALE, self.SYNTH_W * SCALE)
        arr = np.arange(np.prod(shape), dtype=np.float32).reshape(shape) / 1000.0
        return arr, shape

    def _path_and_sha(self, tmp, data):
        path = os.path.join(tmp, "synthetic_raw.f32le")
        with open(path, "wb") as f:
            f.write(data)
        return path, sha256_bytes(data)

    def test_valid_loads_exact_3d_chw(self):
        arr, shape = self._synthetic()
        data = arr.astype("<f4").tobytes()
        with tempfile.TemporaryDirectory() as tmp:
            path, sha = self._path_and_sha(tmp, data)
            loaded = load_full_reference_f32le(path, sha, "synthetic", self.SYNTH_W, self.SYNTH_H)
            self.assertEqual(loaded.shape, shape)
            self.assertEqual(loaded.ndim, 3)
            self.assertTrue(np.array_equal(loaded, arr))

    def test_wrong_sha_fails(self):
        arr, _ = self._synthetic()
        data = arr.astype("<f4").tobytes()
        with tempfile.TemporaryDirectory() as tmp:
            path, _ = self._path_and_sha(tmp, data)
            with self.assertRaises(SystemExit) as cm:
                load_full_reference_f32le(path, "0" * 64, "synthetic", self.SYNTH_W, self.SYNTH_H)
            self.assertIn("SHA-256 mismatch", str(cm.exception))

    def test_truncated_byte_count_fails(self):
        arr, _ = self._synthetic()
        data = arr.astype("<f4").tobytes()
        data = data[:-1]
        with tempfile.TemporaryDirectory() as tmp:
            path, sha = self._path_and_sha(tmp, data)
            with self.assertRaises(SystemExit) as cm:
                load_full_reference_f32le(path, sha, "synthetic", self.SYNTH_W, self.SYNTH_H)
            self.assertIn("byte count", str(cm.exception))

    def test_oversized_byte_count_fails(self):
        arr, _ = self._synthetic()
        data = arr.astype("<f4").tobytes()
        data = data + b"\x00\x00\x00\x00"
        with tempfile.TemporaryDirectory() as tmp:
            path, sha = self._path_and_sha(tmp, data)
            with self.assertRaises(SystemExit) as cm:
                load_full_reference_f32le(path, sha, "synthetic", self.SYNTH_W, self.SYNTH_H)
            self.assertIn("byte count", str(cm.exception))


class TestPreflightE(unittest.TestCase):
    """E. one designated decomposition output tile missing -> rejected"""

    def test_missing_decomposition_output_tile(self):
        with tempfile.TemporaryDirectory() as tmp:
            device_dir = os.path.join(tmp, "device")
            tiles_dir = os.path.join(device_dir, "tiles", "seam_stress_188x188")
            os.makedirs(tiles_dir)
            # Create all 4 input tiles
            for i in range(4):
                inp = os.path.join(tiles_dir, f"tile_{i}_input.f32le")
                with open(inp, "wb") as f:
                    f.write(b"x" * INPUT_TILE_BYTES)
            # Create only 3 output tiles (tile_2 missing)
            for i in [0, 1, 3]:
                out = os.path.join(tiles_dir, f"tile_{i}_output.f32le")
                with open(out, "wb") as f:
                    f.write(b"y" * OUTPUT_TILE_BYTES)
            # Validation should fail
            ok, err = validate_decomposition_artifacts(device_dir, "seam_stress_188x188", 188, 188)
            self.assertFalse(ok)
            self.assertIn("expected 4 output tiles, found 3", err)

    def test_missing_decomposition_input_tile(self):
        with tempfile.TemporaryDirectory() as tmp:
            device_dir = os.path.join(tmp, "device")
            tiles_dir = os.path.join(device_dir, "tiles", "smooth_257x257")
            os.makedirs(tiles_dir)
            # Create all 16 output tiles
            for i in range(16):
                out = os.path.join(tiles_dir, f"tile_{i}_output.f32le")
                with open(out, "wb") as f:
                    f.write(b"y" * OUTPUT_TILE_BYTES)
            # Create only 15 input tiles (tile_5 missing)
            for i in range(16):
                if i == 5:
                    continue
                inp = os.path.join(tiles_dir, f"tile_{i}_input.f32le")
                with open(inp, "wb") as f:
                    f.write(b"x" * INPUT_TILE_BYTES)
            ok, err = validate_decomposition_artifacts(device_dir, "smooth_257x257", 257, 257)
            self.assertFalse(ok)
            self.assertIn("expected 16 input tiles, found 15", err)

    def test_wrong_decomposition_tile_size(self):
        with tempfile.TemporaryDirectory() as tmp:
            device_dir = os.path.join(tmp, "device")
            tiles_dir = os.path.join(device_dir, "tiles", "seam_stress_188x188")
            os.makedirs(tiles_dir)
            for i in range(4):
                inp = os.path.join(tiles_dir, f"tile_{i}_input.f32le")
                with open(inp, "wb") as f:
                    f.write(b"x" * INPUT_TILE_BYTES)
                out = os.path.join(tiles_dir, f"tile_{i}_output.f32le")
                with open(out, "wb") as f:
                    f.write(b"y" * (OUTPUT_TILE_BYTES + 100))  # wrong size
            ok, err = validate_decomposition_artifacts(device_dir, "seam_stress_188x188", 188, 188)
            self.assertFalse(ok)
            self.assertIn("output size", err)

    def test_production_missing_decomposition_output_rejects(self):
        with tempfile.TemporaryDirectory() as tmp:
            device_dir = os.path.join(tmp, "device")
            tiles_dir = os.path.join(device_dir, "tiles", "seam_stress_188x188")
            os.makedirs(tiles_dir)
            for i in range(4):
                inp = os.path.join(tiles_dir, f"tile_{i}_input.f32le")
                with open(inp, "wb") as f:
                    f.write(b"x" * INPUT_TILE_BYTES)
            for i in [0, 1, 3]:
                out = os.path.join(tiles_dir, f"tile_{i}_output.f32le")
                with open(out, "wb") as f:
                    f.write(b"y" * OUTPUT_TILE_BYTES)
            with self.assertRaises(SystemExit) as cm:
                require_decomposition_artifacts(device_dir, "seam_stress_188x188", 188, 188)
            self.assertIn("seam_stress_188x188", str(cm.exception))


class TestCanonicalCardinality(unittest.TestCase):
    """Verify canonical corpus cardinality: 25 fixtures, 280 tiles."""

    def test_fixture_count_25(self):
        manifest = make_canonical_fixtures_manifest()
        ok, err = validate_canonical_fixture_count(manifest)
        self.assertTrue(ok, err)

    def test_planned_tile_count_280(self):
        manifest = make_canonical_fixtures_manifest()
        ok, err = validate_planned_tile_count(manifest)
        self.assertTrue(ok, err)


if __name__ == "__main__":
    unittest.main()