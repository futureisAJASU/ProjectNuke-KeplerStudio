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

STABLE LOCATION (outside repo):
  %USERPROFILE%\.projectnuke\keplerstudio\keplerstudio-stable-debug.jks

BEHAVIOR
1. Detect Android SDK / adb / apksigner / keytool.
2. If installed, obtain installed base APK cert SHA-256 via apksigner --print-certs (never uninstall).
3. Read default debug keystore %USERPROFILE%\.android\debug.keystore cert SHA-256 via keytool.
4. If stable target exists: verify its cert; if installed app exists require target == installed.
5. If stable does NOT exist and app is installed: require default debug keystore cert == installed cert, then copy existing key to stable target.
6. If certs differ: STOP, print both fingerprints, do NOT uninstall or create replacement.
7. If no app installed: bootstrap from existing default debug.keystore.

Support importing a known matching keystore via -ImportKeystorePath (never commit it).
#>
param(
    [string]$ImportKeystorePath = "",
    [string]$ImportStorePass = "android",
    [string]$ImportKeyAlias = "androiddebugkey",
    [string]$ImportKeyPass = "android"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Find-Executable([string[]]$candidates) {
    foreach ($c in $candidates) {
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    return $null
}

function Find-ApkSigner {
    $candidates = @("apksigner", "apksigner.bat")
    $fromPath = Find-Executable $candidates
    if ($fromPath) { return $fromPath }
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
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

function Get-CertSha256FromKeystore([string]$keystorePath, [string]$storePass, [string]$alias) {
    $keytool = Find-Executable @("keytool", "keytool.exe")
    if (-not $keytool) { throw "keytool not found on PATH (JDK required)" }
    if (-not (Test-Path $keystorePath)) { return $null }
    $out = & $keytool -list -v -keystore $keystorePath -storepass $storePass -alias $alias 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        # Try without alias (list all)
        $out = & $keytool -list -v -keystore $keystorePath -storepass $storePass 2>&1 | Out-String
    }
    # keytool prints SHA256: XX:XX:... ; apksigner prints SHA-256 digest: hex
    $sha = $null
    if ($out -match "SHA256:\s*([0-9A-F:]+)") { $sha = $Matches[1].Trim() }
    elseif ($out -match "SHA-256.*?:\s*([0-9a-fA-F:]+)") { $sha = $Matches[1].Trim() }
    if ($sha) {
        $sha = $sha -replace ":", "" -replace "\s", ""
        return $sha.ToLower()
    }
    # Fallback: parse Certificate fingerprints line
    if ($out -match "Certificate fingerprints:") {
        # Next lines contain SHA256
    }
    throw "Unable to parse SHA-256 from keytool output for $keystorePath`n$out"
}

function Get-InstalledCertSha256([string]$apksignerPath, [string]$adbPath, [string]$packageName) {
    # Check if package installed
    $pmOut = & $adbPath shell pm path $packageName 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $pmOut -notmatch "package:") {
        Write-Host "No installed package $packageName found on connected device."
        return $null
    }
    $apkDevicePath = ($pmOut -split "`n" | Where-Object { $_ -match "package:" } | Select-Object -First 1).Trim() -replace "package:", ""
    if (-not $apkDevicePath) { return $null }
    Write-Host "Installed APK device path: $apkDevicePath"
    $tmp = Join-Path $env:TEMP "kepler_installed_base.apk"
    # Pull base APK without modification
    & $adbPath pull $apkDevicePath $tmp 2>&1 | Out-String | Write-Host
    if (-not (Test-Path $tmp)) { throw "Failed to pull installed APK from $apkDevicePath" }
    $verifyOut = & $apksignerPath verify --print-certs $tmp 2>&1 | Out-String
    # Parse SHA-256 digest
    $sha = $null
    if ($verifyOut -match "SHA-256 digest:\s*([0-9a-fA-F]+)") { $sha = $Matches[1].ToLower().Trim() }
    elseif ($verifyOut -match "SHA256:\s*([0-9A-Fa-f:]+)") { $sha = ($Matches[1] -replace ":", "").ToLower().Trim() }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    if (-not $sha) {
        Write-Host $verifyOut
        throw "Unable to parse installed APK cert SHA-256 from apksigner output"
    }
    return $sha
}

# --- main ---
$packageName = "com.projectnuke.keplerstudio"
$stableDir = Join-Path $env:USERPROFILE ".projectnuke\keplerstudio"
$stableKeystore = Join-Path $stableDir "keplerstudio-stable-debug.jks"
$stableStorePass = "android"
$stableKeyAlias = "androiddebugkey"
$stableKeyPass = "android"
$defaultDebugKeystore = Join-Path $env:USERPROFILE ".android\debug.keystore"
$defaultStorePass = "android"
$defaultAlias = "androiddebugkey"

Write-Host "=== KeplerStudio Stable DEBUG Keystore Bootstrap ==="
Write-Host "Package: $packageName"
Write-Host "Stable target: $stableKeystore"
Write-Host "Default debug keystore: $defaultDebugKeystore"

$adb = Find-Executable @("adb", "adb.exe")
if (-not $adb) { Write-Warning "adb not found on PATH — will skip installed-app check (bootstrap from default keystore only)" }
$apksigner = Find-ApkSigner
if (-not $apksigner) { Write-Warning "apksigner not found — will skip installed-app check" }

$installedSha = $null
if ($adb -and $apksigner) {
    $adbDevices = & $adb devices 2>&1 | Out-String
    Write-Host $adbDevices
    if ($adbDevices -match "`n\S+\s+device") {
        try {
            $installedSha = Get-InstalledCertSha256 $apksigner $adb $packageName
            if ($installedSha) { Write-Host "Installed cert SHA-256: $installedSha" }
        } catch {
            Write-Warning "Failed to obtain installed cert: $_"
        }
    } else {
        Write-Host "No device connected — skipping installed-app check."
    }
} else {
    Write-Host "Skipping installed-app check (tool missing)."
}

$defaultSha = $null
if (Test-Path $defaultDebugKeystore) {
    try {
        $defaultSha = Get-CertSha256FromKeystore $defaultDebugKeystore $defaultStorePass $defaultAlias
        Write-Host "Default debug keystore SHA-256: $defaultSha"
    } catch {
        Write-Warning "Failed to read default debug keystore: $_"
    }
} else {
    Write-Host "Default debug keystore not found at $defaultDebugKeystore"
}

$stableSha = $null
if (Test-Path $stableKeystore) {
    try {
        $stableSha = Get-CertSha256FromKeystore $stableKeystore $stableStorePass $stableKeyAlias
        Write-Host "Stable keystore SHA-256: $stableSha"
    } catch {
        Write-Warning "Failed to read stable keystore: $_"
    }
}

# Import path support
if ($ImportKeystorePath -ne "") {
    if (-not (Test-Path $ImportKeystorePath)) { throw "Import keystore not found: $ImportKeystorePath" }
    $importSha = Get-CertSha256FromKeystore $ImportKeystorePath $ImportStorePass $ImportKeyAlias
    Write-Host "Import keystore SHA-256: $importSha"
    if ($installedSha -and $importSha -ne $installedSha) {
        Write-Error "Import keystore cert $importSha does NOT match installed app cert $installedSha — refusing to pin."
        exit 1
    }
    New-Item -ItemType Directory -Force -Path $stableDir | Out-Null
    Copy-Item -LiteralPath $ImportKeystorePath -Destination $stableKeystore -Force
    Write-Host "Imported $ImportKeystorePath -> $stableKeystore"
    $stableSha = $importSha
    Write-Host "Pinned SHA-256: $stableSha"
    exit 0
}

# Decision matrix
if (Test-Path $stableKeystore) {
    # Stable exists — verify
    if ($installedSha -and $stableSha -and $installedSha -ne $stableSha) {
        Write-Host ""
        Write-Error "MISMATCH: stable target cert != installed app cert"
        Write-Host "  Stable  SHA-256: $stableSha"
        Write-Host "  Installed SHA-256: $installedSha"
        Write-Host "ACTION REQUIRED: Locate the private keystore that signed the installed app and re-run with -ImportKeystorePath <path-to-matching-jks>."
        Write-Host "Do NOT uninstall the app, do NOT create a replacement key."
        exit 1
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
        Write-Error "Installed app exists but default debug keystore not readable — cannot bootstrap. Locate the keystore that signed the installed app and import it."
        exit 1
    }
    if ($defaultSha -ne $installedSha) {
        Write-Host ""
        Write-Error "MISMATCH: default debug keystore cert != installed app cert"
        Write-Host "  Default SHA-256: $defaultSha"
        Write-Host "  Installed SHA-256: $installedSha"
        Write-Host "ACTION REQUIRED: Locate the matching old private keystore (.jks) that signed the installed app"
        Write-Host "and run: .\tools\pin_debug_keystore.ps1 -ImportKeystorePath <path>"
        Write-Host "Do NOT uninstall, do NOT create a replacement key."
        exit 1
    }
    # Copy existing default key to stable target to preserve lineage
    New-Item -ItemType Directory -Force -Path $stableDir | Out-Null
    Copy-Item -LiteralPath $defaultDebugKeystore -Destination $stableKeystore -Force
    # Also copy .properties if exists? default debug keystore has no extra
    Write-Host ""
    Write-Host "Bootstrapped stable keystore from default debug keystore (certs matched)."
    $newSha = Get-CertSha256FromKeystore $stableKeystore $stableStorePass $stableKeyAlias
    Write-Host "Pinned SHA-256: $newSha"
    exit 0
} else {
    # No app installed — bootstrap from default
    if (-not (Test-Path $defaultDebugKeystore)) {
        Write-Host "No installed app and no default debug keystore — generating stable keystore via keytool..."
        New-Item -ItemType Directory -Force -Path $stableDir | Out-Null
        $keytool = Find-Executable @("keytool", "keytool.exe")
        if (-not $keytool) { throw "keytool not found" }
        & $keytool -genkeypair -keystore $stableKeystore -storepass $stableStorePass -keypass $stableKeyPass -alias $stableKeyAlias -keyalg RSA -keysize 2048 -validity 9125 -dname "CN=KeplerStudio Debug, OU=ProjectNuke, O=ProjectNuke, L=Debug, S=Debug, C=KR" 2>&1 | Write-Host
        if ($LASTEXITCODE -ne 0) { throw "keytool genkeypair failed" }
        $newSha = Get-CertSha256FromKeystore $stableKeystore $stableStorePass $stableKeyAlias
        Write-Host "Generated stable keystore SHA-256: $newSha"
        exit 0
    } else {
        New-Item -ItemType Directory -Force -Path $stableDir | Out-Null
        Copy-Item -LiteralPath $defaultDebugKeystore -Destination $stableKeystore -Force
        $newSha = Get-CertSha256FromKeystore $stableKeystore $stableStorePass $stableKeyAlias
        Write-Host ""
        Write-Host "Bootstrapped stable keystore from existing default debug keystore (no app installed)."
        Write-Host "Pinned SHA-256: $newSha"
        exit 0
    }
}
