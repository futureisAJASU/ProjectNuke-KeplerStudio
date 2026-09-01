#Requires -Version 5.1
<#
.SYNOPSIS
Bootstrap a stable DEBUG keystore for com.projectnuke.keplerstudio so that
future `adb install -r` updates do not require uninstall/data loss.

RULES
- applicationId stays com.projectnuke.keplerstudio (no suffix)
- NEVER use this key for release
- Do NOT commit private .jks or passwords to Git
- Do NOT automatically uninstall the installed app
- Do NOT silently fallback to a different debug key if pinned key is missing
- FAIL-CLOSED whenever the installed app's cert cannot be verified: the script must
  distinguish "no device" from "package absent" from "package present but cert read
  failed". A present-but-unreadable package is treated as an unknown lineage and the
  script STOPS without touching any keystore.

STABLE LOCATION (outside repo):
  %USERPROFILE%\.projectnuke\keplerstudio\keplerstudio-stable-debug.jks

BEHAVIOR
1. Detect Android SDK / adb / apksigner / keytool.
2. Probe DEVICE state and PACKAGE state independently of apksigner (adb shell pm path).
3. If the package is PRESENT:
   - apksigner missing  -> STOP (cannot prove installed lineage).
   - pkg path / pull / apksigner / parse failure -> STOP (cert read FAILED).
   - otherwise continue with the installed cert SHA-256.
4. If stable target exists: verify its cert; if installed app exists require target == installed.
5. If stable does NOT exist and app is installed: require default debug keystore cert == installed cert,
   then copy existing key to stable target.
6. If certs differ: STOP, print both fingerprints, do NOT uninstall or create replacement.
7. If no app installed (or no device): bootstrap from existing default debug.keystore;
   generate a key only when no default keystore exists at all.

IMPORT CREDENTIAL POLICY (explicit A):
Gradle hardcodes the debug credentials in app/build.gradle.kts:
    storePassword = android
    keyAlias      = androiddebugkey
    keyPassword   = android
Imported keystores must therefore use EXACTLY those credentials. Any other
storepass/alias/keypass is rejected, and the imported store is validated with keytool
before it is copied. A keystore with different credentials is never written to the
stable location.
#>
param(
    [string]$ImportKeystorePath = "",
    [string]$ImportStorePass = "android",
    [string]$ImportKeyAlias = "androiddebugkey",
    [string]$ImportKeyPass = "android"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Credentials Gradle hardcodes for the debug build; the stable keystore must use exactly these.
$gradleStorePass = "android"
$gradleKeyAlias = "androiddebugkey"
$gradleKeyPass = "android"

function Find-Executable([string[]]$candidates) {
    foreach ($c in $candidates) {
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    return $null
}

# Run a native executable, capturing stdout+stderr text and the exit code.
# Must NOT let stderr records trip ErrorActionPreference=Stop (adb/apksigner write
# progress/warnings to stderr on SUCCESS). Use SilentlyContinue so native stderr
# does not surface as a PowerShell error record.
function Invoke-Native([string]$exe, [string[]]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $output = ""
    $exitCode = -1
    try {
        $output = & $exe @arguments 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    } catch {
        $output = "native invocation error: $($_.Exception.Message)"
        $exitCode = -1
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ Output = $output; ExitCode = [int]$exitCode }
}

function Find-ApkSigner {
    $candidates = @("apksigner", "apksigner.bat")
    $fromPath = Find-Executable $candidates
    if ($fromPath) { return $fromPath }
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) {
        # Fallback: parse sdk.dir from the project's local.properties (untracked, user-local).
        $localProps = Join-Path (Split-Path -Parent $PSScriptRoot) "local.properties"
        if (Test-Path $localProps) {
            foreach ($line in (Get-Content $localProps)) {
                if ($line -match "^sdk\.dir\s*=\s*(.+)$") {
                    $raw = $Matches[1].Trim()
                    # Java properties escaping used by Gradle: \\ -> \, \: -> :, etc.
                    $sentinel = [string][char]1
                    $raw = $raw -replace '\\\\', $sentinel
                    $raw = $raw -replace '\\(.)', '$1'
                    $sdk = $raw.Replace($sentinel, '\')
                    break
                }
            }
        }
    }
    if ($sdk) {
        $bt = Join-Path $sdk "build-tools"
        if (Test-Path $bt) {
            $latest = Get-ChildItem $bt -Directory | Sort-Object Name -Descending | Select-Object -First 1
            if ($latest) {
                foreach ($name in $candidates) {
                    $p = Join-Path $latest.FullName $name
                    if (Test-Path $p) { return $p }
                }
            }
        }
    }
    return $null
}

function Stop-FailClosed([string]$title, [string]$detail) {
    $host.UI.WriteLine("")
    $host.UI.WriteLine("STABLE DEBUG SIGNING STOPPED (fail-closed): $title")
    $host.UI.WriteLine($detail)
    $host.UI.WriteLine("Actions that will NOT happen: copy default debug.keystore, generate a new key,")
    $host.UI.WriteLine("overwrite the stable key, or uninstall the app.")
    exit 1
}

function Get-CertSha256FromKeystore([string]$keystorePath, [string]$storePass, [string]$alias) {
    $keytool = Find-Executable @("keytool", "keytool.exe")
    if (-not $keytool) { throw "keytool not found on PATH (JDK required)" }
    if (-not (Test-Path $keystorePath)) { return $null }
    $kt = Invoke-Native $keytool @("-list", "-v", "-keystore", $keystorePath, "-storepass", $storePass, "-alias", $alias)
    $out = $kt.Output
    if ($kt.ExitCode -ne 0) {
        throw "keytool cannot read $keystorePath (storepass/alias mismatch or corrupt store, exit=${$kt.ExitCode}): $out"
    }
    $sha = $null
    if ($out -match "SHA256:\s*([0-9A-F:]+)") { $sha = $Matches[1].Trim() }
    elseif ($out -match "SHA-256.*?:\s*([0-9a-fA-F:]+)") { $sha = $Matches[1].Trim() }
    if (-not $sha) { throw "Unable to parse SHA-256 from keytool output for $keystorePath`n$out" }
    return ($sha -replace ":", "" -replace "\s", "").ToLower()
}

function Get-InstalledApkDevicePath([string]$adbPath, [string]$packageName) {
    # $null => package absent, "" => probe failed. Only a "package:" line proves presence.
    $res = Invoke-Native $adbPath @("shell", "pm path $packageName")
    if ($res.ExitCode -ne 0) { return "" }
    $lines = @($res.Output -split "`n" | Where-Object { $_ -match "^\s*package:" })
    if (-not $lines) { return $null }
    return ($lines | Select-Object -First 1).Trim() -replace "^package:", ""
}

function Get-InstalledCertSha256([string]$apksignerPath, [string]$adbPath, [string]$packageName) {
    # Throws on ANY failure: pull, apksigner verify, or parse. Caller treats a throw
    # as "package present but cert read FAILED" and stops.
    $devicePath = Get-InstalledApkDevicePath $adbPath $packageName
    if (-not $devicePath) { throw "installed APK device path missing or probe failed" }
    Write-Host "Installed APK device path: $devicePath"
    $tmp = Join-Path $env:TEMP "kepler_installed_base.apk"
    if (Test-Path $tmp) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
    $pull = Invoke-Native $adbPath @("pull", $devicePath, $tmp)
    if (-not (Test-Path $tmp)) { throw "Failed to pull installed APK from $devicePath (exit=${$pull.ExitCode}): $($pull.Output)" }
    try {
        $verify = Invoke-Native $apksignerPath @("verify", "--print-certs", $tmp)
        $verifyOut = $verify.Output
        # Ignore warning lines emitted to stderr by apksigner (may be prefixed with "apksigner.bat : WARNING:").
        $cleanOut = $verifyOut -split "`n" | Where-Object { $_ -notmatch "WARNING:" } | Out-String
        if ($verify.ExitCode -ne 0) { throw "apksigner verify failed (exit=${$verify.ExitCode}): $verifyOut" }
        # Build-tools 36+ may omit the "Verified using" banner but still print signer certs on success.
        # Consider verification successful if a SHA-256 digest is present and exit code was 0.
        $hasVerifiedBanner = $cleanOut -match "Verified using v[\d]+ scheme"
        $hasSha = $cleanOut -match "SHA-256 digest:\s*[0-9a-fA-F]+" -or $cleanOut -match "SHA256:\s*[0-9A-Fa-f:]+"
        if (-not $hasVerifiedBanner -and -not $hasSha) { throw "apksigner did not report a verified signature: $verifyOut" }
        $sha = $null
        if ($cleanOut -match "SHA-256 digest:\s*([0-9a-fA-F]+)") { $sha = $Matches[1] }
        elseif ($cleanOut -match "SHA256:\s*([0-9A-Fa-f:]+)") { $sha = ($Matches[1] -replace ":", "") }
        if (-not $sha) { throw "Unable to parse installed APK cert SHA-256 from apksigner output: $verifyOut" }
        return $sha.ToLower().Trim()
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
}

# --- main ---
$packageName = "com.projectnuke.keplerstudio"
$stableDir = Join-Path $env:USERPROFILE ".projectnuke\keplerstudio"
$stableKeystore = Join-Path $stableDir "keplerstudio-stable-debug.jks"
$defaultDebugKeystore = Join-Path $env:USERPROFILE ".android\debug.keystore"

Write-Host "=== KeplerStudio Stable DEBUG Keystore Bootstrap ==="
Write-Host "Package: $packageName"
Write-Host "Stable target: $stableKeystore"
Write-Host "Default debug keystore: $defaultDebugKeystore"

$adb = Find-Executable @("adb", "adb.exe")
$apksigner = Find-ApkSigner

# Device / package state — probed without apksigner.
# deviceState:    "none" | "connected"
# packageState:   $null (unknown: no adb or no device) | $true (installed) | $false (absent)
$deviceState = "none"
$packageState = $null
if (-not $adb) {
    Write-Host "adb not found on PATH — device/package state unknown; bootstrap decision uses local keystores only."
} else {
    $adbDevices = & $adb devices 2>&1 | Out-String
    Write-Host $adbDevices
    if ($adbDevices -match "`n\S+\s+device") {
        $deviceState = "connected"
        $pkg = Get-InstalledApkDevicePath $adb $packageName
        $packageState = ($pkg -ne $null)
        if ($packageState) { Write-Host "Package PRESENT on connected device." }
        else { Write-Host "Connected device, package ABSENT." }
    } else {
        Write-Host "No device connected — skipping installed-app check."
    }
}

# Installed cert SHA-256 — ONLY when the package is proven present.
$installedSha = $null
if ($deviceState -eq "connected" -and $packageState -eq $true) {
    if (-not $apksigner) {
        Stop-FailClosed "installed package present but apksigner is missing" "adb proves $packageName is installed, but apksigner cannot be located to verify its signing certificate. Install the Android build-tools (or add apksigner to PATH) and re-run. Refusing to guess the installed lineage."
    }
    try {
        $installedSha = Get-InstalledCertSha256 $apksigner $adb $packageName
        Write-Host "Installed cert SHA-256: $installedSha"
    } catch {
        Stop-FailClosed "installed package present but cert read FAILED" "Error: $_"
    }
    if (-not $installedSha) {
        Stop-FailClosed "installed package present but cert read FAILED" "apksigner returned no SHA-256 digest."
    }
}

$defaultSha = $null
if (Test-Path $defaultDebugKeystore) {
    try {
        $defaultSha = Get-CertSha256FromKeystore $defaultDebugKeystore $gradleStorePass $gradleKeyAlias
        Write-Host "Default debug keystore SHA-256: $defaultSha"
    } catch {
        # Default keystore unreadable is only FATAL when the installed lineage depends on it;
        # otherwise bootstrap proceeds from generation. Surface the trouble, don't hide it.
        Write-Warning "Failed to read default debug keystore: $_"
    }
} else {
    Write-Host "Default debug keystore not found at $defaultDebugKeystore"
}

$stableSha = $null
if (Test-Path $stableKeystore) {
    try {
        $stableSha = Get-CertSha256FromKeystore $stableKeystore $gradleStorePass $gradleKeyAlias
        Write-Host "Stable keystore SHA-256: $stableSha"
    } catch {
        Stop-FailClosed "stable keystore present but unreadable" "Error: $_"
    }
}

# Import path support (explicit credential policy A)
if ($ImportKeystorePath -ne "") {
    if (-not (Test-Path $ImportKeystorePath)) { throw "Import keystore not found: $ImportKeystorePath" }
    if ($ImportStorePass -ne $gradleStorePass -or $ImportKeyAlias -ne $gradleKeyAlias -or $ImportKeyPass -ne $gradleKeyPass) {
        Write-Error "Import keystore must use the exact Gradle debug credentials (storepass=$gradleStorePass alias=$gradleKeyAlias keypass=$gradleKeyPass). Got storepass='$ImportStorePass' alias='$ImportKeyAlias' keypass='$ImportKeyPass'. Convert the keystore first if needed."
        exit 1
    }
    # Fail closed: imported store must actually be readable with exactly those credentials.
    $importSha = $null
    try {
        $importSha = Get-CertSha256FromKeystore $ImportKeystorePath $ImportStorePass $ImportKeyAlias
        if (-not $importSha) { throw "imported keystore has no certificate for alias $ImportKeyAlias" }
        # Verify the private key is present and readable under the declared keypass.
        $keytool = Find-Executable @("keytool", "keytool.exe")
        $privOut = & $keytool -list -v -keystore $ImportKeystorePath -storepass $ImportStorePass -alias $ImportKeyAlias 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $privOut -notmatch "PrivateKeyEntry|PrivateKeyEntry:|Private key algorithm") {
            throw "imported keystore alias $ImportKeyAlias does not hold a private key entry"
        }
    } catch {
        Write-Error "Import validation failed: $_"
        exit 1
    }
    Write-Host "Import keystore SHA-256: $importSha"
    if ($installedSha -and $importSha -ne $installedSha) {
        Write-Error "Import keystore cert $importSha does NOT match installed app cert $installedSha — refusing to pin."
        exit 1
    }
    if ($stableSha -and $importSha -ne $stableSha) {
        Stop-FailClosed "stable keystore already exists with a different cert" "Stable SHA-256: $stableSha / Import SHA-256: $importSha. Refusing to overwrite an existing stable key with a different lineage."
    }
    New-Item -ItemType Directory -Force -Path $stableDir | Out-Null
    Copy-Item -LiteralPath $ImportKeystorePath -Destination $stableKeystore -Force
    Write-Host "Imported $ImportKeystorePath -> $stableKeystore"
    Write-Host "Pinned SHA-256: $importSha"
    exit 0
}

# Decision matrix
if (Test-Path $stableKeystore) {
    # Stable exists — verify
    if ($installedSha -and $stableSha -and $installedSha -ne $stableSha) {
        Stop-FailClosed "MISMATCH: stable target cert != installed app cert" "  Stable  SHA-256: $stableSha`n  Installed SHA-256: $installedSha`nACTION REQUIRED: Locate the private keystore that signed the installed app and re-run with -ImportKeystorePath <path-to-matching-jks> (it must use the exact Gradle debug credentials)."
    }
    Write-Host ""
    Write-Host "Stable keystore already exists and is authoritative."
    Write-Host "Pinned SHA-256: $stableSha"
    exit 0
}

# Stable does NOT exist
if ($installedSha) {
    # Installed app exists — require default == installed, then copy
    if (-not $defaultSha) {
        Stop-FailClosed "installed app exists but default debug keystore not readable" "Cannot bootstrap the stable key while the installed app's lineage is known. Locate the keystore that signed the installed app and import it with -ImportKeystorePath (exact Gradle debug credentials)."
    }
    if ($defaultSha -ne $installedSha) {
        Stop-FailClosed "MISMATCH: default debug keystore cert != installed app cert" "  Default SHA-256: $defaultSha`n  Installed SHA-256: $installedSha`nACTION REQUIRED: Locate the matching old private keystore (.jks) that signed the installed app and run:`n.\tools\pin_debug_keystore.ps1 -ImportKeystorePath <path>"
    }
    # Copy existing default key to stable target to preserve lineage
    New-Item -ItemType Directory -Force -Path $stableDir | Out-Null
    Copy-Item -LiteralPath $defaultDebugKeystore -Destination $stableKeystore
    Write-Host ""
    Write-Host "Bootstrapped stable keystore from default debug keystore (certs matched)."
    $newSha = Get-CertSha256FromKeystore $stableKeystore $gradleStorePass $gradleKeyAlias
    if ($newSha -ne $installedSha) {
        Stop-FailClosed "post-copy verification failed" "Stable SHA-256 after copy: $newSha does not match installed SHA-256 $installedSha."
    }
    Write-Host "Pinned SHA-256: $newSha"
    exit 0
} else {
    # No app installed (or no device) — bootstrap from default
    if (-not (Test-Path $defaultDebugKeystore)) {
        if ($deviceState -eq "connected" -and $packageState -eq $true) {
            # Unreachable (installedSha would be set), but keep fail-closed as a backstop.
            Stop-FailClosed "internal state: package present but no installed cert" "Refusing to generate a fresh key while an installed app may rely on an unknown lineage."
        }
        Write-Host "No installed app and no default debug keystore — generating stable keystore via keytool..."
        New-Item -ItemType Directory -Force -Path $stableDir | Out-Null
        $keytool = Find-Executable @("keytool", "keytool.exe")
        if (-not $keytool) { throw "keytool not found" }
        & $keytool -genkeypair -keystore $stableKeystore -storepass $gradleStorePass -keypass $gradleKeyPass -alias $gradleKeyAlias -keyalg RSA -keysize 2048 -validity 9125 -dname "CN=KeplerStudio Debug, OU=ProjectNuke, O=ProjectNuke, L=Debug, S=Debug, C=KR" 2>&1 | Write-Host
        if ($LASTEXITCODE -ne 0) { throw "keytool genkeypair failed" }
        $newSha = Get-CertSha256FromKeystore $stableKeystore $gradleStorePass $gradleKeyAlias
        Write-Host "Generated stable keystore SHA-256: $newSha"
        exit 0
    } else {
        New-Item -ItemType Directory -Force -Path $stableDir | Out-Null
        Copy-Item -LiteralPath $defaultDebugKeystore -Destination $stableKeystore
        $newSha = Get-CertSha256FromKeystore $stableKeystore $gradleStorePass $gradleKeyAlias
        Write-Host ""
        Write-Host "Bootstrapped stable keystore from existing default debug keystore (no app installed / no device)."
        Write-Host "Pinned SHA-256: $newSha"
        exit 0
    }
}
