#!/usr/bin/env python3
"""
Reproducible gzip helper for N3/N4 retained evidence containers.

gzip output normally embeds the source file's mtime in its header, so the same
bytes compressed on different dates produce different container hashes. For
evidence containers whose hash is recorded in a manifest, that timestamp
non-determinism makes the recorded container SHA go stale even when the
underlying tensor is unchanged.

This helper uses `gzip.compress`/`gzip.decompress` with a fixed `mtime=0` and a
pinned compresslevel (default 9), so the container bytes are a pure function of
the payload bytes.

Usage:
  python reproducible_gzip.py compress <payload> <out.gz>
  python reproducible_gzip.py decompress <in.gz> [out]
  python reproducible_gzip.py sha <file>            # print payload-or-gz sha
"""
import gzip
import hashlib
import sys

COMPRESSLEVEL = 9


def compress(payload_path, out_path, level=COMPRESSLEVEL):
    with open(payload_path, "rb") as f:
        payload = f.read()
    blob = gzip.compress(payload, compresslevel=level, mtime=0)
    with open(out_path, "wb") as f:
        f.write(blob)
    return payload, blob


def decompress(in_path, out_path=None):
    with open(in_path, "rb") as f:
        blob = f.read()
    payload = gzip.decompress(blob)
    if out_path:
        with open(out_path, "wb") as f:
            f.write(payload)
    return blob, payload


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "compress":
        payload, blob = compress(sys.argv[2], sys.argv[3])
        print(f"payload={len(payload)} bytes  payload_sha256={sha256_bytes(payload)}")
        print(f"gz={len(blob)} bytes  gz_sha256={sha256_bytes(blob)}")
    elif cmd == "decompress":
        blob, payload = decompress(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
        print(f"gz={len(blob)} bytes  payload={len(payload)} bytes  payload_sha256={sha256_bytes(payload)}")
    elif cmd == "sha":
        b = open(sys.argv[2], "rb").read()
        print(f"bytes={len(b)} sha256={sha256_bytes(b)}")
    else:
        raise SystemExit(f"unknown command: {cmd}")