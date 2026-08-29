"""Pinned N3-accepted GENERAL checkpoint identity (single source of truth).

Both ``n4_reference.py`` (host reference generation) and ``compare_n4.py``
(device correctness comparison) import these constants so no script can
silently substitute a different weight identity. The S24 GENERAL NNC was
compiled from this exact upstream checkpoint, so the N4 device comparison must
load the same weights — never the deterministic seeded geometry weights.
"""

# SHA-256 of the exact N3-accepted upstream GENERAL checkpoint file.
GENERAL_CHECKPOINT_SHA256 = "8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292"

# The committed/upstream checkpoint filename produced by Real-ESRGAN release tooling.
GENERAL_CHECKPOINT_FILENAME = "realesr-general-x4v3.pth"

# Upstream Real-ESRGAN commit that produced the accepted checkpoint.
UPSTREAM_COMMIT = "a4abfb2979a7bbff3f69f58f58ae324608821e27"

# Pinned architecture string shared with the N3 reference manifest.
ARCHITECTURE = (
    "SRVGGNetCompact("
    "num_in_ch=3,num_out_ch=3,num_feat=64,num_conv=32,upscale=4,act_type=prelu"
    ")"
)

# State-dict key used to load the official checkpoint (no params_ema present).
STATE_DICT_KEY = "params"

# Weight identity labels recorded in the N4 reference manifest.
WEIGHTS_IDENTITY_GENERAL = "GENERAL"
WEIGHTS_IDENTITY_GEOMETRY = "deterministic-seeded-geometry-only"