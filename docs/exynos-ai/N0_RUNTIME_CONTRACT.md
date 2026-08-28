# N0 — Samsung Runtime + NNC Contract Evidence (Real_ESRGAN_General_x4v3, Exynos 2400 / Galaxy S24)

Status: **PASS for adapter implementation** (N1). Two facts remain device-verifiable and are
explicitly listed in "Unresolved facts"; they gate N2 PASS, not N1 implementation.
All runtime API facts below are taken from Samsung's own official public distribution,
not from blogs or third-party mirrors.

---

## 1. Source / evidence inventory

| # | Source | Type | What it proves |
|---|--------|------|----------------|
| S1 | https://github.com/exynos-eco/enn-sdk-samples (Samsung `exynos-eco` org; Kotlin; pushed 2026-07-14) | Official Samsung sample repo (root LICENSE: MIT, "(c) Samsung Electronics") | Full ENN SDK Android integration: headers, client `.so`, JNI reference source, Gradle/CMake wiring, **and the committed NNC asset** |
| S2 | `enn-sdk-samples/image-enhancement/Real_ESRGAN_General_x4v3/` inside S1 | Official sample application for the EXACT target model | Model identity, tensor contract, preprocessing/postprocessing, chipset-mismatch behavior |
| S3 | `image-classification/mobilenetv2` in https://github.com/exynos-eco/enn-sdk-samples-v920 | Official sample (MIT) | Same `enn::api` v1 surface used from a JNI wrapper; `libenn_public_api_cpp.so` variant |
| S4 | `app/src/main/cpp/include/enn_api-public_ndk_v1.hpp` + `enn_api-type_ndk_v1.h` (committed in S1/S2/S3) | Official SDK headers | Complete public C++ API contract (function signatures, types, error codes, meta IDs) |
| S5 | https://developer.samsung.com/neural/overview.html | Official Samsung page | Old Samsung Neural SDK: "**no longer provided to third-party developers**" → excluded path |
| S6 | https://developers.google.com/edge/litert/next/samsung (+ soc-developer ai-litecore docs) | Official Google/Samsung docs | Exynos AI LiteCore supports **only Exynos 2500 (E9955) and 2600 (E9965)** → NOT the Exynos 2400 route; proves `NNC ≠ LiteCore` for this device |
| S7 | https://github.com/pytorch/executorch/issues/16395 (closed as Done; Samsung engineers `hoon98-choi`, `Jiseong-oh` respond) | Official vendor statements in a public thread | (a) ExecuTorch/LiteCore Exynos backend supports "from Exynos2500 now", Exynos 2400 "not now"; (b) on commercial Exynos devices test executables are blocked (sepolicy/unrooted) but Samsung directs users to "test your model with android **test app**" — i.e. an APK is the supported third-party vehicle on commercial devices |
| S8 | https://aisdk.developer.samsung.com/overview | Official partner portal | Galaxy On-Device AI Partner SDK requires partner approval → separate restricted path, not required by S1's mechanism |
| S9 | https://soc-developer.semiconductor.samsung.com/global/development/ai-studio (+ ENN SDK section) | Official docs portal | Exynos AI Studio converts models to NNC format; NNC files are chipset-specific; downloads require a (free) Samsung-account login |

Community sources were used only to discover S1/S7; every contract fact comes from S1–S4.

## 2. Runtime identity

* Framework: **ENN** (Exynos Neural Network) framework — the same runtime the On-device AI
  Sample Applications describe ("execution of a converted model using the ENN framework").
* API surface: native C/C++ **public NDK v1 API**, namespace `enn::api`
  (`EnnInitialize`, `EnnOpenModel`, `EnnAllocateAllBuffers`, `EnnExecuteModel`,
  `EnnGetBufferInfoByIndex`, `EnnGetMetaInfo`, … — full list in S4).
* Client library shipped with app: `libenn_public_api_ndk_v1.so` (arm64-v8a, ~13 KB stub).
  The stub binds to the vendor-side ENN implementation at runtime:
  `<uses-native-library android:name="libenn_user.samsung_slsi.so" />`
  (declared in S2's AndroidManifest) — a Samsung LSI **vendor public native library**.
* Execution model: blocking `EnnExecuteModel(model_id)` through the vendor ENN service
  (`ENN_RET_FAILED_SERVICE_NULL`, timeout/recovery error codes confirm service-mediated
  hardware execution). Async variant `EnnExecuteModelAsync` + `EnnExecuteModelWait` exists.
* **This is NOT LiteCore/LiteRT and NOT ExecuTorch** (S6/S7 exclude those for Exynos 2400).
  It is also not the discontinued Samsung Neural SDK (S5).

## 3. Retail third-party accessibility status

Evidence FOR ordinary-APK access:
* S2 is a plain third-party-style APK (no signature permission, no system UID, no partner
  certificate in the manifest). Its only privileged dependency is the **vendor public
  library** declaration `<uses-native-library android:name="libenn_user.samsung_slsi.so"/>`.
  The Android `uses-native-library` mechanism works for any installed APK iff the vendor
  firmware lists that library in its public libraries.
* S7: Samsung's guidance for commercial Exynos devices is to validate via an "android test
  app" — i.e., an APK — because shell executables are blocked by sepolicy on unrooted
  commercial devices. This implies the APK route is the supported access path.

Evidence that must be verified on hardware (see §9):
* Whether retail SM-S921N (Galaxy S24, Exynos 2400) firmware actually ships
  `/vendor/lib64/libenn_user.samsung_slsi.so` and exposes it via
  `/vendor/etc/public.libraries.txt` (or `public.libraries-.txt` variants).
* Whether the committed NNC variant targets Exynos 2400 (NNCs are chipset-specific, S9).

No Samsung account/partner approval is needed for the S1 artifacts themselves (public GitHub,
MIT); the AI Studio Farm/model-detail downloads additionally require only a free Samsung
account login (S9).

## 4. SDK / runtime versions

| Item | Value | Source |
|---|---|---|
| Public API version | NDK **v1** (`enn_api-public_ndk_v1.hpp`, `enn_api-type_ndk_v1.h`, header date 2023-06-12, version 1.0) | S4 |
| Client stub | `libenn_public_api_ndk_v1.so`, arm64-v8a, 13,016 bytes (also `libenn_public_api_cpp.so` variant 134,456 B in S3) | S2/S3 |
| Vendor dependency | `libenn_user.samsung_slsi.so` (device-provided; NOT redistributable — never vendored into the APK) | S2 manifest |
| Sample build | compileSdk 33, minSdk 29, targetSdk 33, AGP 8.x-era, CMake 3.22.1, abiFilters arm64-v8a | S2 build.gradle |
| Model toolchain | Converted by Exynos AI Studio ("ENN SDK service"), NNC format; compiler versions embedded per-model and queryable via `EnnGetMetaInfo(ENN_META_VERSION_MODEL_COMPILER_NNC/NPU, …)` | S9 + S4 |

Exact ABI list: arm64-v8a only (both sample repos set `abiFilters "arm64-v8a"`).

## 5. Packaging contract (as proven by S2)

```text
app/src/main/cpp/
  enn_jni.cc                      # own JNI wrapper over enn::api (reference impl provided, MIT)
  include/enn_api-public_ndk_v1.hpp
  include/enn_api-type_ndk_v1.h
app/src/main/jniLibs/arm64-v8a/
  libenn_public_api_ndk_v1.so     # vendored client stub (13,016 bytes)
CMakeLists.txt:
  add_library(enn_jni SHARED enn_jni.cc)
  add_library(enn_service_so SHARED IMPORTED)
  set_target_properties(enn_service_so PROPERTIES IMPORTED_LOCATION
      ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libenn_public_api_ndk_v1.so)
  target_link_libraries(enn_jni enn_service_so log-lib)
AndroidManifest.xml:
  <uses-native-library android:name="libenn_user.samsung_slsi.so" />
Kotlin: System.loadLibrary("enn_jni")
```

* No additional Gradle dependency/repository; no AAR; no manifest permissions beyond normal
  storage/camera use of the hosting app; no R8 rules observed in samples (minifyEnabled false);
  Kepler must keep `enn::api` symbols reachable if it ever enables minification (the JNI
  boundary is C++, so R8 does not strip it; only the Kotlin external-fun class names matter).
* Model deployment model: **APK-packaged file copied to app-private storage**
  (`copyNNCFromAssetsToInternalStorage()` then `EnnOpenModel(filesDir/<name>)`).
* minSdk implication: samples use 29; Kepler-compatible.

### 5a. Multi-session prepared-file audit (static)

**Question:** Can two concurrent `ExynosUpscaleSession` instances interfere with each other's
prepared model file when one session closes and deletes its file while the other is still using it?

**Audit outcome (Option B — per-session isolation):** The adapter implements session-exclusive
prepared file paths. Each `ExynosUpscaleSession` instance prepares its model into a uniquely named
file `<baseName>.session-<N>` where `<N>` is a per-process monotonic sequence. Teardown deletes
ONLY the session's own file.

**Rationale:** The vendored Samsung contract (S2, S4) does not establish whether the ENN runtime
continues to read the model file after `EnnOpenModel()` returns. Without that guarantee, sharing a
single prepared path across sessions would risk one session deleting a file that another live
session's runtime handle still depends on.

**Safety mechanism:** Path exclusivity — not timing assumptions. No session can delete another
live session's model file because they never share the same path. This holds regardless of how
long the ENN framework keeps the file open internally.

**Leak bounds:** Each session's prepared file is deleted on successful close. On crash, orphaned
prepared files may remain in `filesDir/exynos_models/` bounded by the number of crashed session
lifetimes in a single process lifetime (typical: zero; pathological: O(crash count) × ~3 MB each).
Future cleanup policy may sweep stale `session-*` files on startup if needed.

**Prepared-file deletion truth:** Teardown classifies the delete outcome truthfully —
`Deleted`, `AlreadyAbsent`, `DeleteFailed`, or `Threw`. A `delete()` that returns `false` while
the file still exists is `DeleteFailed`, never silently treated as clean. On `DeleteFailed`/`Threw`
the path is retained as session-owned cleanup debt until an explicit later `close()` retry; the
session reference is never dropped while the file may physically remain.

## 6. Active production NNC identity

The active production asset is the FP16 variant supplied through the official Samsung
model portal with `Chipset = Exynos 2400`. "FP16" identifies the compiled model variant;
the verified external tensor interface remains FP32 and Kepler's FP32 CHW preprocessing is
unchanged.

| Field | Active FP16 production asset |
|---|---|
| Logical APK asset | `models/exynos/Real-ESRGAN-General-x4v3.nnc` |
| Portal model | `Real_ESRGAN_General_x4v3` |
| Source | Samsung official model portal |
| Selected chipset | Exynos 2400 |
| Variant | FP16 |
| Byte size | 3,112,960 |
| SHA-256 | `9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12` |
| File header | `ENNC` |
| Embedded compiler target | `--compiler NPU`, `--framework SNC`, `--soc-type Root`, `--chip_version EVT1`, `--schema_version v2` |
| NPUC version | `v2.4.11.l` |
| Embedded model name | `real_esrgan_general_x4v3_simplify` |
| Input tensor | FLOAT32 CHW `1 x 3 x 128 x 128` |
| Output tensor | FLOAT32 CHW `1 x 3 x 512 x 512` |

### 6a. Deferred quantized comparison artifact

The Samsung portal Quantized Exynos-2400 variant is recorded but deliberately not wired
into production before FP16 N2A passes: 1,867,776 bytes, SHA-256
`81968b6a2c6963f081c27d4c843c57ebd0de493d3bb7fa706f2872fdb8840196`,
Root / EVT1, NPUC `v2.4.11.l`, `QUANT_MODE=ASYMM`, and FLOAT32 CHW external I/O
`1 x 3 x 128 x 128` to `1 x 3 x 512 x 512`. No internal quantization bit-width is
assumed without runtime or official artifact evidence.

### 6b. Artifact-selection status

The FP16 asset above supersedes the former GitHub sample for production and N2A. The
historical sample is retained below solely to preserve the prior device rejection evidence;
it is not an active model and must not be restored.

## 6c. Rejected wrong-chipset sample (historical only)

| Field | Value |
|---|---|
| File name | `Real-ESRGAN-General-x4v3.nnc` |
| Byte size | 2,867,200 |
| SHA-256 | `1A36E24C91B33358A437D432CFC5E57A5CCDE1A683CE80DD00A13FD00DBB0C37` |
| Provenance | Committed by Samsung in S2 at `image-enhancement/Real_ESRGAN_General_x4v3/app/src/main/assets/` |
| Model catalog page | `soc-developer.semiconductor.samsung.com/global/solution/ai/models/detail/36ad7134-5621-48b2-8ddf-e4889417f6ef` (linked from S2 README; SPA content behind login) |
| License/distribution | Upstream model Real-ESRGAN (xinntao) is BSD-3-Clause; the compiled NNC binary is distributed publicly by Samsung in the MIT-licensed sample repo. Redistribute inside Kepler APK only after confirming packaging/license expectations; until then treat as pinned build input. |
| **Chipset targeting** | **Embedded compiler metadata records `--soc-type Solomon --chip_version EVT1`. "Solomon" is publicly associated with Exynos 2500 / S5E9955. The current SM-S921N target is Exynos 2400 / S5E9945. The committed binary is therefore NOT an Exynos-2400-targeted variant.** |

### 6a. Embedded metadata (from binary inspection of current committed file)

```
--compiler NPU --snc HW_Real_ESRGAN_general_x4v3_new_snc_7.16.17.21.snc
--framework SNC --soc-type Solomon --input_conversion hw_cfu --output_conversion hw_icfu
--multicore true --cfs false --chip_version EVT1 --schema_version v3
```

### 6b. Root-cause classification (updated 2026-08-27)

The previous N2 hardware run on SM-S921N (Exynos 2400 / S5E9945) failed at
`EnnOpenModel()` with return -1. Root cause: **chipset mismatch**. The committed
Samsung GitHub sample binary targets Solomon (Exynos 2500 / S5E9955), not the
S24's S5E9945. NNCs are chipset-specific; the ENN runtime on S24 rejects the
Solomon variant.

The file is NOT corrupt, NOT a placeholder, and NOT dimensionally incompatible
(input/output tensors remain 3×128×128 / 3×512×512 FP32). It is simply the wrong
chipset variant.

### Original required action (completed by the active FP16 repin)

Replace the committed NNC with an **Exynos-2400-targeted variant** of
`Real_ESRGAN_General_x4v3`. Samsung's model-detail page at S9 lists the model
for Exynos 2400 as a separate download selection (Chipset = Exynos 2400). The
downloaded artifact must be:

- verified real binary (not Git LFS pointer, not placeholder)
- embedded metadata showing `--soc-type` consistent with S5E9945 / Exynos 2400
- identical tensor contract (3×128×128 → 3×512×512 FP32) or updated accordingly
- then re-pinned in `ModelAssetManifest` and this contract section

This was the pre-repin hardware gate. The active FP16 portal artifact above now satisfies
the missing-artifact prerequisite; N2A hardware results remain separately required.

The user-reported earlier local download was searched for narrowly (Downloads, Desktop,
project model dirs): **not found**; the official identical artifact above supersedes it.

## 7. Input tensor contract (from S2 `ModelConstants.kt` + preProcess())

| Field | Value |
|---|---|
| Tensor count | 1 input buffer (`buffer_set[0]`) |
| Shape | fixed **CHW = 3 × 128 × 128** (H=128, W=128, C=3) |
| Dtype | FP32, little/native-endian byte buffer |
| Channel order | RGB (R = `(argb shr 16)`, G = `(shr 8)`, B = `(shr 0)`) written CHW |
| Normalization | pixel/255.0 → range [0, 1]; no mean/std, no offset (`INPUT_CONVERSION_OFFSET = 0`) |
| Buffer size | 128·128·3·4 = 196,608 bytes |
| Alignment/stride | dense packed float array written into `buffer_set[i]->va`; no stride param exposed |
| Source of truth | Samsung sample code for THIS nnc; do NOT inherit PyTorch Real-ESRGAN conventions |

Runtime verification hooks available (S4): `EnnGetBufferInfoByIndex(OUT, model_id, ENN_DIR_IN, 0)`
returns `{n, width, height, channel, size}` — the N1 adapter validates shape+size before use.

## 8. Output tensor contract (from S2 `postProcess()`)

| Field | Value |
|---|---|
| Tensor count | first output buffer = `buffer_set[n_in_buf]` |
| Shape | fixed **CHW = 3 × 512 × 512** (x4 spatial vs input) |
| Dtype | FP32 native-endian |
| Range | [0, 1] float (sample coerces `coerceIn(0f,1f)` then ×255 to 8-bit RGB) |
| Buffer size | 512·512·3·4 = 3,145,728 bytes (read length from `buffer_set[i]->size`) |
| Ownership | buffer owned by ENN until `EnnReleaseBuffers`; sample copies out via memcpy |

## 9. NPU proof mechanism (for N2.7)

Layered proof, all authoritative:

1. **Vendor-service execution**: `enn::api` has no CPU/GPU fallback switch; execution goes
   through the ENN vendor service to the NPU pipeline. Error codes
   (`ENN_RET_FAILED_SERVICE_NULL`, `ENN_RET_FAILED_TIMEOUT_ENN/DD/FW/HW_*`) identify the
   hardware path explicitly. A successful `EnnExecuteModel` return (0) is itself
   service-mediated execution, not silent CPU fallback.
2. **`EnnGetMetaInfo`**: returns per-model strings for
   `ENN_META_VERSION_MODEL_COMPILER_NNC`, `…_MODEL_COMPILER_NPU`, `…_UNIFIED_FW`,
   `…_NPU_FW` — recorded in the probe report as loaded-model/NPU-stack identity evidence.
3. **logcat**: the ENN framework/service emits ENN-tagged logs during open/execute on device
   (captured in the N2 report).
4. `EnnSetPreferencePerfMode(ENN_PREF_MODE_*)` documents NPU performance modes.

CPU fallback misreporting is structurally impossible through this API surface: there is no
delegate-selection parameter to misuse.

## 10. Unresolved facts (must be resolved on hardware or via login-gated portal)

| # | Fact | How to resolve |
|---|---|---|
| U1 | Does SM-S921N retail firmware ship `libenn_user.samsung_slsi.so` and expose it as a vendor public library? | `adb shell ls -l /vendor/lib64/libenn_user.samsung_slsi.so; adb shell cat '/vendor/etc/public.libraries.txt'` (+ `*.txt` variants), then install the N2 probe APK |
| U2 | Is the committed NNC variant valid for Exynos 2400? | Empirical: `EnnOpenModel` + `EnnAllocateAllBuffers` success on the S24; record `EnnGetMetaInfo`. If invalid: download the Exynos-2400 variant from the model-detail page (free Samsung account) and re-pin SHA |
| U3 | Formal statement of retail redistribution terms for ENN-enabled APKs | Only obtainable from Samsung (portal/EULA). Not required for bring-up probing; flagged for production release |

## 11. Decision

**N0 GATE: PASS (for N1).** The complete official API, packaging recipe, reference
implementation, and the exact model binary were obtained from Samsung's official public
distribution. A REAL runtime adapter can be written against `enn::api` NDK v1 without any
invented symbol. U1/U2 gate the N2 hardware PASS only; both have concrete one-command
verification steps and map to truthful capability states
(`RuntimeUnavailable` / `UnsupportedDevice` / `AssetInvalid`) if they fail.
