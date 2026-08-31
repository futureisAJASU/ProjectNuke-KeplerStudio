# KeplerStudio — Stable DEBUG Signing

**Package:** `com.projectnuke.keplerstudio`  
**Scope:** DEBUG only. Release signing is independent and MUST NOT use this key.

## Goal
Keep the development `com.projectnuke.keplerstudio` installation signed with ONE stable
certificate so future debug reinstalls can use `adb install -r` without `adb uninstall`
and without losing app data.

## Location (outside the repo)
```
%USERPROFILE%\.projectnuke\keplerstudio\keplerstudio-stable-debug.jks
```
- alias `androiddebugkey`, storepass `android`, keypass `android` (same convention as default `~/.android/debug.keystore` so lineage is preserved when bootstrapping by copy)
- Gradle `app/build.gradle.kts` points `buildTypes.debug.signingConfig` at this path.
- If the file is missing, `assembleDebug`/`packageDebug` FAILS CLOSED — no silent fallback to `~/.android/debug.keystore`.

## Bootstrap

```powershell
# 1) Connect S24 (if installed app exists, the script checks its cert without uninstalling)
.\tools\pin_debug_keystore.ps1

# 2) If you have located the old matching keystore that signed the currently installed app:
.\tools\pin_debug_keystore.ps1 -ImportKeystorePath C:\path\to\old-debug.jks
```

Decision logic (see `tools/pin_debug_keystore.ps1` header comments):
- If stable keystore already exists → verify its cert; if an app is installed, require `stable == installed`, otherwise authoritative.
- If stable does NOT exist and an app is installed → require `default debug keystore cert == installed cert`, then copy that EXISTING key to stable.
- If certs differ → STOP, print both SHA-256 fingerprints, do NOT uninstall, do NOT create replacement — you must locate/import the matching old private keystore.
- If no app is installed → bootstrap from existing `~/.android/debug.keystore`.

## Validation

```powershell
.\gradlew :app:signingReport --console=plain --no-daemon
.\gradlew :app:assembleDebug --console=plain --no-daemon
# Verify built APK cert == pinned stable cert
& "$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat" verify --print-certs app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\debug\app-debug.apk  # must succeed without uninstall
```

## Public fingerprint record (safe to track)

> SHA-256 is public metadata. The private `.jks` file and passwords are NOT tracked.

Fill after first pin (example placeholder):
```
Pinned stable DEBUG cert SHA-256: <paste hex after pin_debug_keystore.ps1 output>
Store path: %USERPROFILE%\.projectnuke\keplerstudio\keplerstudio-stable-debug.jks
Alias: androiddebugkey
```

## Rules enforced
- `applicationId` stays `com.projectnuke.keplerstudio` (no suffix)
- `release` signing is NOT configured with this key
- No `.jks` or passwords are committed
- No automatic uninstall or data clear
- No silent fallback to `~/.android/debug.keystore` after pinning
