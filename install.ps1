# jk installer (Windows / PowerShell)
#
# Usage:
#   irm https://jumpkick.build/install.ps1 | iex
#   powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://jumpkick.build/install.ps1 | iex"
#   .\install.cmd [path\to\jk.exe|.zip|lib\jk-<version>.jar]   # recommended locally (bypasses Restricted policy)
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 [path\to\jk.exe|.zip|.jar]
#   pwsh -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 path\to\jk.exe
#
# Environment variables:
#   JK_ARCHIVE_URL   Override the archive URL. JK_VERSION is required; signed evidence
#                    still comes from JK_RELEASES_URL\<version>\. Supports .zip and .jar.
#   JK_CLIENT        `native` or `jvm`. Unset: the native jk.exe (x64; ARM64 runs it under
#                    emulation). `jvm` installs the JVM client instead — jk-<version>.jar on a
#                    JDK 25+ you provide, as bin\jk.bat — native speed on Windows on ARM.
#   JK_JAVA_HOME     The JDK the JVM client runs on (else JAVA_HOME, else java on the PATH).
#   JK_RELEASES_URL  Override the release site root (mirrors).
#   JK_VERSION       Install a specific version instead of the latest.
#   JK_HOME          jk's home directory. Default %USERPROFILE%\.jk; everything jk owns lives
#                    under it. The client is installed to $JK_HOME\bin.
#   JK_NONINTERACTIVE / CI  Force non-interactive path.
#   JK_LOCAL_PATH    Local binary/archive when invoking via irm|iex (no positional args).
#   JK_SET_EXECUTION_POLICY=1  Apply `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` when
#                    the current policy would block profile hooks (same as -SetExecutionPolicy).
#                    By default the installer only prints that command.
#   JK_RC=1          Write the profile block and the User PATH entry for an install whose
#                    JK_HOME is not the default %USERPROFILE%\.jk (same as -Rc). An install into
#                    the default home always writes them; a private home leaves the profiles and
#                    the User PATH alone and prints the line to run instead.
#
# On-disk layout (JkDirs): one home, %USERPROFILE%\.jk, holding bin\, cache\, config\,
# config.toml, creds\, lib\, state\ and store\ — the same tree on every platform.
# See docs/user/install.md.
#
# Requires Windows PowerShell 5.1+ or PowerShell 7+.

#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $LocalPath = "",

    # Skip engine warm-up (CI / PATH-only install).
    [switch] $SkipEngineWarm,

    # Install the JVM client (jk-<version>.jar as bin\jk.bat) instead of the native jk.exe. Same as
    # JK_CLIENT=jvm for irm|iex callers.
    [switch] $Jvm,

    # Persist a CurrentUser RemoteSigned execution policy when the effective one would block
    # profile hooks. Off by default: the installer prints the command instead of changing a
    # setting no uninstall reverts. irm|iex callers use JK_SET_EXECUTION_POLICY=1.
    [switch] $SetExecutionPolicy,

    # Write the `# >>> jk installer >>>` profile block and the User PATH entry for an install whose
    # JK_HOME is not the default %USERPROFILE%\.jk. The default home always writes them; a private
    # home leaves the profiles and the User PATH alone and prints the line to run instead.
    # irm|iex callers use JK_RC=1.
    [switch] $Rc,

    # Network-free CI seam: authenticate files without installing or executing them.
    [string] $VerifyOnlyDirectory = "",
    [string] $VerifyOnlyArtifactName = "",
    [string] $TestRsaModulus = "",
    [string] $TestRsaExponent = ""
)

$ErrorActionPreference = "Stop"

# ---- colors / logging ------------------------------------------------------
#
# ANSI escapes need PS 7+ (`e) or an explicit ESC char. Keep ASCII glyphs on
# Windows PowerShell 5.1 / redirected hosts so we never print tofu boxes.

$esc = [char]27
$supportsVt = $false
try {
    $supportsVt = [bool]$Host.UI.SupportsVirtualTerminal
} catch {
    $supportsVt = $false
}
$script:UseColor = (-not $env:NO_COLOR) -and ($PSVersionTable.PSVersion.Major -ge 7 -or $supportsVt) -and ($Host.Name -eq "ConsoleHost")
if ($script:UseColor) {
    $script:BOLD = "$esc[1m"; $script:RED = "$esc[31m"; $script:GREEN = "$esc[32m"
    $script:YELLOW = "$esc[33m"; $script:DIM = "$esc[2m"; $script:RESET = "$esc[0m"
    $script:DOT = [string][char]0x25CF; $script:CROSS = [string][char]0x2716
} else {
    $script:BOLD = ""; $script:RED = ""; $script:GREEN = ""; $script:YELLOW = ""
    $script:DIM = ""; $script:RESET = ""
    $script:DOT = "*"; $script:CROSS = "x"
}

function Write-Info([string] $Message) {
    Write-Host ("{0}{1}{2} {3}" -f $script:GREEN, $script:DOT, $script:RESET, $Message)
}
function Write-Note([string] $Message) {
    Write-Host ("{0}    {1}{2}" -f $script:DIM, $Message, $script:RESET)
}
function Write-Err([string] $Message) {
    Write-Host ("{0}{1}{2} {3}" -f $script:RED, $script:CROSS, $script:RESET, $Message) -ForegroundColor Red
}
function Die([string] $Message) {
    Write-Err $Message
    # The download scratch directory exists from source resolution on; a refusal must not leave it.
    if ($script:tmpRoot -and (Test-Path -LiteralPath $script:tmpRoot)) {
        Remove-Item -LiteralPath $script:tmpRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
    exit 1
}

# ---- install dir -----------------------------------------------------------
#
# One home, one bin. `jk activate` writes $JK_HOME\bin\jk into the profile and `jk self update`
# replaces the binary there, so this has to be the directory JkDirs.binDirectory() resolves —
# which it is, by having one answer.

$JkHome = if ($env:JK_HOME) { $env:JK_HOME } else { Join-Path $HOME ".jk" }
# Same refusal as JkDirs: a relative JK_HOME would install somewhere jk itself will not read.
if (-not [IO.Path]::IsPathRooted($JkHome)) { Die "JK_HOME must be an absolute path: $JkHome" }
$InstallDir = Join-Path $JkHome "bin"

$ReleasesUrl = if ($env:JK_RELEASES_URL) { $env:JK_RELEASES_URL.TrimEnd("/") } else { "https://jumpkick.build/releases" }
$ReleaseRsaModulus = "rqF4qTQyhVx8JWBv4+MTZbpwqu7G2luUCqcxIsViJ+0OsLGRe5LpnlL26coWX8QYlF/1nx7ZA42tQWXLFtWAwbNeIEpFRYqEM5ikpHoWkXq1rlgCjR35QSkvn22dndFBwbZ1tQUhbuvIDq6qnymKwsbOXBbbTqWDCRRflo/XtQ1K0kQMlDJ49/iz1v0e0druyhC2XtxXNfEq04UOfO+scwKt2dIry7K2F4Rxx9NTEhX1RqC55YcUy/amK2r6bor0tsZcfkvjeQoIDP/EceNahJtJOWo4mY3MO3LGfIDcI8f1drPbHjV7hfiFZHzf91nqi+VSWRLd3IMGEY7NHy+4pXE7RYLPT5QjAznM+eZIb88NldXIjMiCJRujT9QbhN54yseUKwmZ1i+5EjFWIXg9tb41+RP7A2XQsikl0XS/dtuZC6rMNCe2YBvOhyGcSR51niEdY503RXsJW4aTQNma5JkdkraEuMVN5SC/8R17hNHWx6RS/H/3e32HXe3ycC9f"
$ReleaseRsaExponent = "AQAB"
# The release this installer ships with. A signed latest-release pointer naming anything older is
# a rollback — a bucket writer or a mirror re-serving an old, validly signed release — and is
# refused; JK_VERSION remains the deliberate way to install a specific release.
$ReleaseFloor = "0.13.8"

# irm|iex cannot pass positional args; allow JK_LOCAL_PATH as the local-dist seam.
if (-not $LocalPath -and $env:JK_LOCAL_PATH) {
    $LocalPath = $env:JK_LOCAL_PATH
}

# ---- helpers ---------------------------------------------------------------

function Test-Command([string] $Name) {
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

# The OS architecture as .NET reports it, or "" when the runtime cannot say. Windows PowerShell
# 5.1 on an older .NET Framework has no RuntimeInformation type, and a null there must fall through
# to PROCESSOR_ARCHITECTURE instead of failing the install before its first line of output.
function Get-OsArchitectureName {
    try {
        return [string]([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture)
    } catch {
        return ""
    }
}

function Get-JkTarget {
    # Releases publish windows-x86_64 only. Windows on ARM runs that build under x64 emulation,
    # so an ARM64 host installs it and is told so rather than asking for an artifact that does
    # not exist. The inputs are parameters so the mapping is testable off the host.
    param(
        [string] $ArchName = (Get-OsArchitectureName),
        # Fallback for older hosts / unusual report strings.
        [string] $ProcessorArchitecture = $env:PROCESSOR_ARCHITECTURE
    )
    $arm64 = ($ArchName -match "^Arm64$") -or
        ($ArchName -notmatch "^(X64|Amd64)$" -and $ProcessorArchitecture -match "(?i)ARM64")
    $x64 = ($ArchName -match "^(X64|Amd64)$") -or ($ProcessorArchitecture -match "(?i)AMD64|X86")
    if ($arm64) {
        Write-Note "Windows on ARM64: no windows-aarch64 release exists yet; installing the windows-x86_64 build (runs under x64 emulation). JK_CLIENT=jvm installs the JVM client on an ARM64 JDK instead."
    } elseif (-not $x64) {
        Die "unsupported architecture: $ArchName (supported: x86_64; ARM64 installs the x86_64 build; JK_CLIENT=jvm installs the JVM client on any JDK 25+)"
    }
    return "windows-x86_64"
}

# The feature version a `java -version` first line reports: `openjdk version "25.0.1" 2025-10-21`
# is 25, `java version "1.8.0_392"` is 8; $null when the line says nothing usable.
function Get-JavaMajor([string] $VersionLine) {
    if ($VersionLine -match 'version "(\d+)(?:\.(\d+))?') {
        if ($Matches[1] -eq "1" -and $Matches[2]) { return [int]$Matches[2] }
        return [int]$Matches[1]
    }
    return $null
}

# The JDK the JVM client runs on: JK_JAVA_HOME, else JAVA_HOME, else java on the PATH — a full JDK
# (the build engine forks javac from it) of at least 25, the release the client is compiled for.
# jk installs JDKs itself only for hosts the JDK feed covers; this path exists for the others, so
# the JDK is the user's to provide. Returns the java.exe path.
# Runs a native program and returns its stdout and stderr as plain lines. Windows PowerShell 5.1
# turns every redirected stderr line into an ErrorRecord, and under this script's Stop preference
# the first one (java writes -version there; git its progress) would end the install — so the
# preference is Continue in this scope only, and the records are read back as their text.
function Invoke-NativeLines([string] $Program, [string[]] $Arguments) {
    $ErrorActionPreference = "Continue"
    $lines = @()
    & $Program @Arguments 2>&1 | ForEach-Object {
        if ($_ -is [System.Management.Automation.ErrorRecord]) { $lines += $_.ToString() } else { $lines += [string]$_ }
    }
    return $lines
}

function Find-Java([switch] $Probe) {
    # With -Probe a missing or too-old JDK is $null rather than a refusal, so a caller can decide.
    $java = $null
    if ($env:JK_JAVA_HOME) {
        $java = Join-Path $env:JK_JAVA_HOME "bin\java.exe"
    } elseif ($env:JAVA_HOME) {
        $java = Join-Path $env:JAVA_HOME "bin\java.exe"
    } else {
        $cmd = Get-Command java -ErrorAction SilentlyContinue
        if (-not $cmd) { if ($Probe) { return $null }; Die "the JVM client needs a JDK 25 or newer: set JAVA_HOME (or JK_JAVA_HOME) to one and re-run." }
        $java = $cmd.Source
    }
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
        if ($Probe) { return $null }; Die "no java.exe at $java - set JAVA_HOME (or JK_JAVA_HOME) to a JDK 25 or newer and re-run."
    }
    $firstLine = [string](Invoke-NativeLines $java @("-version") | Select-Object -First 1)
    $major = Get-JavaMajor $firstLine
    if (-not $major) { if ($Probe) { return $null }; Die "could not read a Java version from '$java -version'; set JAVA_HOME (or JK_JAVA_HOME) to a JDK 25 or newer and re-run." }
    if ($major -lt 25) { if ($Probe) { return $null }; Die "$java is Java $major; the JVM client needs a JDK 25 or newer - set JAVA_HOME (or JK_JAVA_HOME) to one and re-run." }
    $homeLine = Invoke-NativeLines $java @("-XshowSettings:properties", "-version") | Where-Object { $_ -match '^\s*java\.home = (.+)$' } | Select-Object -First 1
    if ($homeLine -and ([string]$homeLine -match '^\s*java\.home = (.+)$')) {
        $javaHome = $Matches[1].Trim()
        if (-not (Test-Path -LiteralPath (Join-Path $javaHome "bin\javac.exe") -PathType Leaf)) {
            if ($Probe) { return $null }; Die "$javaHome is a JRE (no bin\javac.exe); jk's build engine needs a full JDK 25 or newer - set JAVA_HOME (or JK_JAVA_HOME) to one and re-run."
        }
    }
    return $java
}

# Windows PowerShell 5.1 repaints its progress bar on every received chunk, which makes a
# multi-megabyte Invoke-WebRequest many times slower. The preference is set in the function
# scope, so the caller's session keeps its own value.

function Save-Url([string] $Url, [string] $OutFile) {
    $ProgressPreference = "SilentlyContinue"
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $OutFile
}

function Get-StrictManifestHash {
    param(
        [Parameter(Mandatory = $true)][byte[]] $ManifestBytes,
        [Parameter(Mandatory = $true)][string] $ArtifactName
    )
    try {
        $utf8 = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList @($false, $true)
        $text = $utf8.GetString($ManifestBytes)
    } catch {
        throw "release SHA256SUMS is not valid UTF-8"
    }
    $lines = $text.Split([char]10)
    $seen = @{}
    $found = $null
    $matchCount = 0
    for ($i = 0; $i -lt $lines.Length; $i++) {
        $line = $lines[$i]
        if ($line.Length -eq 0 -and $i -eq ($lines.Length - 1)) { continue }
        if ($line -notmatch '^([0-9A-Fa-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)$') {
            throw "release SHA256SUMS has a malformed entry"
        }
        $name = $Matches[2]
        if ($seen.ContainsKey($name)) {
            throw "release SHA256SUMS has a duplicate entry for $name"
        }
        $seen[$name] = $true
        if ($name -ceq $ArtifactName) {
            $matchCount++
            $found = $Matches[1].ToLowerInvariant()
        }
    }
    if ($matchCount -ne 1) {
        throw "release SHA256SUMS has no unique exact entry for $ArtifactName"
    }
    return $found
}

# Whether the signed SHA256SUMS names $ArtifactName. Releases are published platform by platform,
# so a version can be live for Linux while its Windows client is not built yet.
function Test-ManifestLists {
    param(
        [Parameter(Mandatory = $true)][byte[]] $ManifestBytes,
        [Parameter(Mandatory = $true)][string] $ArtifactName
    )
    try {
        Get-StrictManifestHash -ManifestBytes $ManifestBytes -ArtifactName $ArtifactName | Out-Null
        return $true
    } catch {
        if ($_.Exception.Message -match 'no unique exact entry') { return $false }
        throw
    }
}

# Verify the release key's RSA/SHA-256 signature in $Signature over the exact $SignedBytes, or
# throw naming $What. Every remote input that steers the install — the latest-release pointer and
# the version directory's SHA256SUMS — passes through here before anything it names is trusted.
function Test-ReleaseSignature {
    param(
        [Parameter(Mandatory = $true)][byte[]] $SignedBytes,
        [Parameter(Mandatory = $true)][string] $Signature,
        [Parameter(Mandatory = $true)][string] $Modulus,
        [Parameter(Mandatory = $true)][string] $Exponent,
        [Parameter(Mandatory = $true)][string] $What
    )
    $signatureBytes = [IO.File]::ReadAllBytes($Signature)
    $signatureText = [Text.Encoding]::ASCII.GetString($signatureBytes)
    if ($signatureText -notmatch '^[A-Za-z0-9+/]+={0,2}\r?\n?$') {
        throw "$What signature is malformed"
    }
    try {
        $signatureValue = [Convert]::FromBase64String($signatureText.TrimEnd([char[]]"`r`n"))
    } catch {
        throw "$What signature is not valid base64"
    }
    if ($signatureValue.Length -ne ([Convert]::FromBase64String($Modulus)).Length) {
        throw "$What signature has the wrong RSA length"
    }

    $parameters = New-Object System.Security.Cryptography.RSAParameters
    $parameters.Modulus = [Convert]::FromBase64String($Modulus)
    $parameters.Exponent = [Convert]::FromBase64String($Exponent)
    $rsa = [Security.Cryptography.RSA]::Create()
    try {
        $rsa.ImportParameters($parameters)
        $valid = $rsa.VerifyData(
            $SignedBytes,
            $signatureValue,
            [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    } finally {
        $rsa.Dispose()
    }
    if (-not $valid) {
        throw "$What signature verification failed; refusing the download"
    }
}

# The version a verified latest-release pointer names. The signature covers the exact bytes, so
# the reading is as literal as the writing: precisely `version <x.y.z>` then `issued <seconds>`,
# LF-terminated, or a refusal. A pointer older than $Floor is a rollback and is refused too.
function Get-ReleasePointerVersion {
    param(
        [Parameter(Mandatory = $true)][string] $Pointer,
        [Parameter(Mandatory = $true)][string] $Signature,
        [Parameter(Mandatory = $true)][string] $Modulus,
        [Parameter(Mandatory = $true)][string] $Exponent,
        [Parameter(Mandatory = $true)][string] $Floor
    )
    $pointerBytes = [IO.File]::ReadAllBytes($Pointer)
    Test-ReleaseSignature -SignedBytes $pointerBytes -Signature $Signature -Modulus $Modulus -Exponent $Exponent `
        -What "latest-release pointer"
    foreach ($byte in $pointerBytes) {
        if ($byte -gt 127) { throw "latest-release pointer is not ASCII" }
    }
    $text = [Text.Encoding]::ASCII.GetString($pointerBytes)
    if ($text -notmatch '^version ([0-9]+\.[0-9]+\.[0-9]+(?:[-.][A-Za-z0-9]+)*)\nissued [0-9]{1,18}\n$') {
        throw "latest-release pointer is malformed; refusing"
    }
    $version = $Matches[1]
    if ([version]($version -replace '-.*', '') -lt [version]($Floor -replace '-.*', '')) {
        throw "latest-release pointer names $version, older than the $Floor this installer ships with; refusing a rolled-back pointer (set JK_VERSION to install a specific release)"
    }
    return $version
}

function Test-ReleaseEvidence {
    param(
        [Parameter(Mandatory = $true)][string] $Artifact,
        [Parameter(Mandatory = $true)][string] $ArtifactName,
        [Parameter(Mandatory = $true)][string] $Manifest,
        [Parameter(Mandatory = $true)][string] $Signature,
        [Parameter(Mandatory = $true)][string] $Modulus,
        [Parameter(Mandatory = $true)][string] $Exponent
    )
    $manifestBytes = [IO.File]::ReadAllBytes($Manifest)
    Test-ReleaseSignature -SignedBytes $manifestBytes -Signature $Signature -Modulus $Modulus -Exponent $Exponent `
        -What "release"

    $expected = Get-StrictManifestHash -ManifestBytes $manifestBytes -ArtifactName $ArtifactName
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $actualBytes = $sha.ComputeHash([IO.File]::ReadAllBytes($Artifact))
    } finally {
        $sha.Dispose()
    }
    $actual = ([BitConverter]::ToString($actualBytes)).Replace("-", "").ToLowerInvariant()
    if ($actual -cne $expected) {
        throw "release archive checksum mismatch for $ArtifactName; refusing the download"
    }
}

if ($VerifyOnlyDirectory) {
    if (-not $VerifyOnlyArtifactName -or $VerifyOnlyArtifactName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') {
        Die "VerifyOnlyArtifactName must be a plain release filename."
    }
    $modulus = if ($TestRsaModulus) { $TestRsaModulus } else { $ReleaseRsaModulus }
    $exponent = if ($TestRsaExponent) { $TestRsaExponent } else { $ReleaseRsaExponent }
    try {
        Test-ReleaseEvidence `
            -Artifact (Join-Path $VerifyOnlyDirectory $VerifyOnlyArtifactName) `
            -ArtifactName $VerifyOnlyArtifactName `
            -Manifest (Join-Path $VerifyOnlyDirectory "SHA256SUMS") `
            -Signature (Join-Path $VerifyOnlyDirectory "SHA256SUMS.sig") `
            -Modulus $modulus `
            -Exponent $exponent
        Write-Info "Release evidence verified."
        return
    } catch {
        Die $_.Exception.Message
    }
}

function Park-IfPresent([string] $Path) {
    if (Test-Path -LiteralPath $Path) {
        $parked = "$Path.old"
        try {
            Move-Item -LiteralPath $Path -Destination $parked -Force
        } catch {
            Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
        }
    }
}

function Expand-JkArchive {
    param(
        [Parameter(Mandatory = $true)][string] $Archive,
        [Parameter(Mandatory = $true)][string] $Destination
    )
    $ext = [IO.Path]::GetExtension($Archive).ToLowerInvariant()
    if ($ext -eq ".zip") {
        $stage = Join-Path ([IO.Path]::GetTempPath()) ("jk-unz-" + [guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Force -Path $stage | Out-Null
        try {
            Expand-Archive -LiteralPath $Archive -DestinationPath $stage -Force
            $exe = Get-ChildItem -Path $stage -Recurse -File -Filter "jk.exe" -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if (-not $exe) {
                $exe = Get-ChildItem -Path $stage -Recurse -File -Filter "jk" -ErrorAction SilentlyContinue |
                    Select-Object -First 1
            }
            if (-not $exe) {
                Die "archive did not contain jk.exe: $Archive"
            }
            Copy-Item -LiteralPath $exe.FullName -Destination $Destination -Force
        } finally {
            Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
        }
        return
    }
    if ($ext -eq ".xz") {
        # PowerShell `>` redirects corrupt binaries (UTF-16). Windows releases ship .zip
        # for this installer; .xz is for self-update via the engine.
        Die "'$Archive' is a .xz file. On Windows use the .zip release (or pass a plain jk.exe)."
    }
    # Plain binary (local dist flow).
    Copy-Item -LiteralPath $Archive -Destination $Destination -Force
}

# Root resolution mirrors JkDirs: JK_STORE_DIR wins, else <home>\store.

function Get-JkStoreRoot {
    if ($env:JK_STORE_DIR) { return $env:JK_STORE_DIR }
    return (Join-Path $JkHome "store")
}

function Invoke-Jk {
    param([Parameter(Mandatory = $true)][string[]] $JkArgs)
    # irm|iex already has the script in memory (unlike curl|bash), but still avoid
    # interactive prompts hanging on a non-console stdin.
    # No `return`: callers read the global $LASTEXITCODE, and a returned integer would ride the
    # pipeline — a call site that shows jk's output would print a stray exit code after it.
    # jk writes diagnostics to stderr; under Stop, Windows PowerShell 5.1 would end the install on
    # the first such line, so the preference is Continue for the duration of the call.
    $ErrorActionPreference = "Continue"
    & $script:JkBin @JkArgs
}

function Clear-NativeExitCode {
    # Best-effort steps (git clone, prefetch) leave a sticky $LASTEXITCODE on
    # Windows PowerShell; clear it so a successful install still exits 0.
    cmd /c "exit 0" | Out-Null
}

function Test-PathEntry([string] $PathVar, [string] $Dir) {
    if ([string]::IsNullOrEmpty($PathVar)) { return $false }
    $want = $Dir.TrimEnd('\')
    foreach ($part in ($PathVar -split ';')) {
        if ([string]::IsNullOrWhiteSpace($part)) { continue }
        if ($part.TrimEnd('\') -ieq $want) { return $true }
    }
    return $false
}

function Broadcast-EnvironmentChange {
    # Tell Explorer / new consoles that User env changed. Already-open terminals
    # keep their old PATH until restarted (same as rustup/uv).
    try {
        if (-not ("JkEnvBroadcast" -as [type])) {
            Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class JkEnvBroadcast {
  [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
  public static extern IntPtr SendMessageTimeout(
      IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam,
      uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
}
"@
        }
        $HWND_BROADCAST = [IntPtr]0xffff
        $WM_SETTINGCHANGE = [uint32]0x1A
        $SMTO_ABORTIFHUNG = [uint32]0x0002
        $result = [UIntPtr]::Zero
        [void][JkEnvBroadcast]::SendMessageTimeout(
            $HWND_BROADCAST, $WM_SETTINGCHANGE, [UIntPtr]::Zero, "Environment",
            $SMTO_ABORTIFHUNG, 5000, [ref]$result)
    } catch {
        # Best-effort: a missed broadcast only means open a new terminal.
    }
}

function Resolve-WriteRc([string] $JkHomeDir, [string] $DefaultHomeDir, [bool] $Requested) {
    # The profile block and the User PATH entry name one home, so only the default home may claim
    # them unasked: a private JK_HOME (a scratch build, a hermetic test, a checkout's own dist)
    # must not turn every new terminal into one that runs it. -Rc / JK_RC=1 asks for them anyway.
    if ($Requested) { return $true }
    $live = [IO.Path]::GetFullPath($JkHomeDir).TrimEnd('\', '/')
    $default = [IO.Path]::GetFullPath($DefaultHomeDir).TrimEnd('\', '/')
    return [string]::Equals($live, $default, [StringComparison]::OrdinalIgnoreCase)
}

function Ensure-UserPath([string] $Dir) {
    # Persist on the User PATH so cmd.exe, PowerShell, and Win32 CreateProcess
    # all resolve jk without a profile. Profile activation still adds hooks.
    $normalized = $Dir.TrimEnd('\')
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if (-not (Test-PathEntry $userPath $normalized)) {
        if ([string]::IsNullOrEmpty($userPath)) {
            $newPath = $normalized
        } else {
            $newPath = $normalized + ";" + $userPath
        }
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
        Broadcast-EnvironmentChange
        Write-Info "Added $normalized to your User PATH"
    } else {
        Write-Note "User PATH already contains $normalized"
    }
    if (-not (Test-PathEntry $env:PATH $normalized)) {
        $env:PATH = $normalized + [IO.Path]::PathSeparator + $env:PATH
    }
}

function Ensure-ProfileExecutionPolicy {
    # jk activate writes $PROFILE; Restricted/AllSigned block loading it.
    # This installer often runs under Process Bypass, so Get-ExecutionPolicy alone
    # is not what a *new* PowerShell session will see — ignore Process scope.
    # A persistent policy change is the user's call: without -Apply the fix is printed, not made.
    param([bool] $Apply = $false)
    $byScope = @{}
    foreach ($e in @(Get-ExecutionPolicy -List)) {
        $byScope[$e.Scope.ToString()] = $e.ExecutionPolicy.ToString()
    }

    foreach ($locked in @('MachinePolicy', 'UserPolicy')) {
        $p = $byScope[$locked]
        if ($p -and $p -ne 'Undefined' -and $p -in @('Restricted', 'AllSigned')) {
            Write-Note "PowerShell execution policy is locked by $locked ($p); profile hooks may not load."
            Write-Note "Ask an admin for RemoteSigned, or try: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned"
            return
        }
    }

    $newSession = 'Restricted'
    foreach ($scope in @('MachinePolicy', 'UserPolicy', 'CurrentUser', 'LocalMachine')) {
        $p = $byScope[$scope]
        if ($p -and $p -ne 'Undefined') {
            $newSession = $p
            break
        }
    }
    if ($newSession -in @('RemoteSigned', 'Unrestricted', 'Bypass')) {
        return
    }

    if (-not $Apply) {
        Write-Note "PowerShell execution policy is $newSession; profile hooks (jk activate) will not load in new sessions."
        Write-Note "To allow them, run: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned"
        Write-Note "(or re-run the installer with -SetExecutionPolicy / JK_SET_EXECUTION_POLICY=1 to apply it)"
        return
    }
    try {
        Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned -Force
        Write-Info "Set PowerShell CurrentUser execution policy to RemoteSigned (so profile hooks can load)"
    } catch {
        Write-Note "Could not set PowerShell execution policy; profile hooks may fail to load."
        Write-Note "Run: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned"
    }
}

# ---- resolve source (URL or local file) ------------------------------------

$tmpRoot = Join-Path ([IO.Path]::GetTempPath()) ("jk-install-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null

$ArchiveUrl = $null
$ArchiveFile = $null
$IsRemote = $false

# Which client: the native jk.exe, or the JVM client (jk-<version>.jar as bin\jk.bat). -Jvm and
# JK_CLIENT=jvm ask for the JVM client; a local file or an explicit URL names its kind by extension.
if ($env:JK_CLIENT -and $env:JK_CLIENT -notin @("native", "jvm")) {
    Die "JK_CLIENT must be 'native' or 'jvm' (got '$($env:JK_CLIENT)')"
}
$UseJvm = [bool]$Jvm -or ($env:JK_CLIENT -eq "jvm")

if ($LocalPath) {
    if (-not (Test-Path -LiteralPath $LocalPath -PathType Leaf)) {
        Die "local file not found: $LocalPath"
    }
    $ArchiveFile = (Resolve-Path -LiteralPath $LocalPath).Path
    $UseJvm = [IO.Path]::GetExtension($ArchiveFile).ToLowerInvariant() -eq ".jar"
} elseif ($env:JK_ARCHIVE_URL) {
    if (-not $env:JK_VERSION) {
        Die "JK_VERSION is required when JK_ARCHIVE_URL is set."
    }
    $version = $env:JK_VERSION
    $ArchiveUrl = $env:JK_ARCHIVE_URL
    $IsRemote = $true
    $UseJvm = ($ArchiveUrl -split '\?')[0].ToLowerInvariant().EndsWith(".jar")
} else {
    $target = if ($UseJvm) { "jvm" } else { Get-JkTarget }
    $version = $env:JK_VERSION
    if (-not $version) {
        # The pointer is signed data and the only mutable input: verified against the release key,
        # read literally, and refused when it names a release older than this installer's own.
        $pointerFile = Join-Path $tmpRoot "LATEST"
        $pointerSignature = Join-Path $tmpRoot "LATEST.sig"
        try {
            Save-Url "$ReleasesUrl/latest/LATEST" $pointerFile
        } catch {
            Die "could not resolve the latest jk version from $ReleasesUrl/latest/LATEST ($($_.Exception.Message))"
        }
        try {
            Save-Url "$ReleasesUrl/latest/LATEST.sig" $pointerSignature
        } catch {
            Die "could not download the latest-release pointer signature from $ReleasesUrl/latest/LATEST.sig ($($_.Exception.Message))"
        }
        try {
            $version = Get-ReleasePointerVersion -Pointer $pointerFile -Signature $pointerSignature `
                -Modulus $ReleaseRsaModulus -Exponent $ReleaseRsaExponent -Floor $ReleaseFloor
        } catch {
            Die $_.Exception.Message
        }
    }
    if (-not $version) {
        Die "could not resolve the latest jk version from $ReleasesUrl/latest/LATEST"
    }
    if (-not $UseJvm) {
        # The version's signed manifest is read before any client download: when it lists no
        # Windows client, the JVM client goes on a JDK 25+ instead of a 404 after the fact.
        $manifestFile = Join-Path $tmpRoot "SHA256SUMS"
        $signatureFile = Join-Path $tmpRoot "SHA256SUMS.sig"
        try {
            Save-Url "$ReleasesUrl/$version/SHA256SUMS" $manifestFile
            Save-Url "$ReleasesUrl/$version/SHA256SUMS.sig" $signatureFile
        } catch {
            Die "failed to download release evidence from $ReleasesUrl/$version ($($_.Exception.Message))"
        }
        $manifestBytes = [IO.File]::ReadAllBytes($manifestFile)
        try {
            Test-ReleaseSignature -SignedBytes $manifestBytes -Signature $signatureFile `
                -Modulus $ReleaseRsaModulus -Exponent $ReleaseRsaExponent -What "release"
        } catch {
            Die $_.Exception.Message
        }
        $nativeName = "jk-$target-$version.zip"
        # A manifest that is not UTF-8, has a malformed line or lists a name twice is a refusal with
        # the reason, not a PowerShell error trace.
        try {
            $listsNative = Test-ManifestLists -ManifestBytes $manifestBytes -ArtifactName $nativeName
        } catch {
            Die $_.Exception.Message
        }
        if (-not $listsNative) {
            $probedJava = Find-Java -Probe
            if ($probedJava) {
                Write-Note "jk $version publishes no $target client yet; installing the JVM client (jk-$version.jar) on $probedJava instead."
                $UseJvm = $true
            } else {
                Die "jk $version publishes no $target client yet, and no JDK 25 or newer was found for the JVM client. Install a JDK 25+ (set JAVA_HOME) and re-run, or set JK_VERSION to a release that has a $target build."
            }
        }
    }
    # Windows installer prefers .zip (no system xz). Self-update uses .xz via the engine. The JVM
    # client is one platform-neutral jar.
    $clientArtifact = if ($UseJvm) { "jk-$version.jar" } else { "jk-$target-$version.zip" }
    $ArchiveUrl = "$ReleasesUrl/$version/$clientArtifact"
    $IsRemote = $true
}
if ($UseJvm) { $script:Java = Find-Java }

if ($IsRemote) {
    if ($version -notmatch '^[A-Za-z0-9._-]+$') {
        Die "invalid release version: $version"
    }
    try {
        $archiveUri = [Uri]$ArchiveUrl
    } catch {
        Die "JK_ARCHIVE_URL must be an absolute HTTPS URL."
    }
    if (-not $archiveUri.IsAbsoluteUri -or $archiveUri.Scheme -ne "https") {
        Die "remote archive URL must use HTTPS."
    }
    $ArtifactName = [IO.Path]::GetFileName($archiveUri.AbsolutePath)
    if ($ArtifactName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') {
        Die "archive URL must end in a plain release artifact filename."
    }
    $ReleaseVersionUrl = "$ReleasesUrl/$version"
}

# ---- download & install ----------------------------------------------------

try {
    if ($IsRemote) {
        $ArchiveFile = Join-Path $tmpRoot "jk.archive"
        # Preserve a useful extension for decompress dispatch.
        $urlExt = [IO.Path]::GetExtension($ArtifactName)
        if ($urlExt) { $ArchiveFile = $ArchiveFile + $urlExt }
        Write-Info "Downloading $ArchiveUrl"
        try {
            Save-Url $ArchiveUrl $ArchiveFile
            $manifestFile = Join-Path $tmpRoot "SHA256SUMS"
            $signatureFile = Join-Path $tmpRoot "SHA256SUMS.sig"
            # The version directory's evidence is fetched once per run: the client probe above
            # already holds it for a release resolved from the pointer or JK_VERSION.
            if (-not ((Test-Path -LiteralPath $manifestFile) -and (Test-Path -LiteralPath $signatureFile))) {
                Save-Url "$ReleaseVersionUrl/SHA256SUMS" $manifestFile
                Save-Url "$ReleaseVersionUrl/SHA256SUMS.sig" $signatureFile
            }
        } catch {
            Die "failed to download release artifact or verification evidence ($($_.Exception.Message))"
        }
        try {
            Test-ReleaseEvidence `
                -Artifact $ArchiveFile `
                -ArtifactName $ArtifactName `
                -Manifest $manifestFile `
                -Signature $signatureFile `
                -Modulus $ReleaseRsaModulus `
                -Exponent $ReleaseRsaExponent
        } catch {
            Die $_.Exception.Message
        }
        # The JVM client's engine jar: fetched here from the same frozen version directory and
        # verified against the same signed sums, then materialized below like a local dist's.
        if ($UseJvm) {
            $engineName = "jk-engine-$version.jar"
            $remoteEngineJar = Join-Path $tmpRoot $engineName
            try {
                Save-Url "$ReleaseVersionUrl/$engineName" $remoteEngineJar
            } catch {
                Die "failed to download $ReleaseVersionUrl/$engineName ($($_.Exception.Message))"
            }
            try {
                Test-ReleaseEvidence `
                    -Artifact $remoteEngineJar `
                    -ArtifactName $engineName `
                    -Manifest $manifestFile `
                    -Signature $signatureFile `
                    -Modulus $ReleaseRsaModulus `
                    -Exponent $ReleaseRsaExponent
            } catch {
                Die $_.Exception.Message
            }
        }
    }

    $displayDir = $InstallDir
    $homePrefix = $HOME
    if ($InstallDir.StartsWith($homePrefix, [StringComparison]::OrdinalIgnoreCase)) {
        $displayDir = "~" + $InstallDir.Substring($homePrefix.Length)
    }

    Write-Host ""
    Write-Info "Installing JumpKick into $displayDir"
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

    $jkxExe = Join-Path $InstallDir "jkx.exe"
    $jkxCmd = Join-Path $InstallDir "jkx.cmd"
    if ($UseJvm) {
        # The JVM client: the jar under <home>\lib\jk\jk-<version>.jar, and the launcher it runs
        # through (bin\jk.bat, plus jkx.cmd) - written by the client itself (`jk self
        # write-launcher`), so the launcher text has one author and the JDK that passed the
        # version check above is the one it bakes in. It parks a leftover jk.exe, which PATHEXT
        # would otherwise keep preferring. A local jar names no version, so the jar is asked.
        $script:JkBin = Join-Path $InstallDir "jk.bat"
        $jarVersion = $version
        if (-not $jarVersion) {
            $answer = [string](Invoke-NativeLines $script:Java @("-jar", $ArchiveFile, "--version") | Select-Object -First 1)
            if ($answer -match '^jk (\S+)') { $jarVersion = $Matches[1] }
            if (-not $jarVersion) { Die "$ArchiveFile does not answer --version like a jk client jar." }
        }
        $jkLib = Join-Path $JkHome "lib\jk"
        New-Item -ItemType Directory -Force -Path $jkLib | Out-Null
        $jkJar = Join-Path $jkLib "jk-$jarVersion.jar"
        try {
            Copy-Item -LiteralPath $ArchiveFile -Destination "$jkJar.tmp" -Force
            Move-Item -LiteralPath "$jkJar.tmp" -Destination $jkJar -Force
        } catch {
            Die "failed to install ${jkJar}: $($_.Exception.Message)"
        }
        # One client jar: a launcher names exactly one. A jar a running client still maps cannot
        # be deleted; it is parked and swept by the next install.
        Get-ChildItem -Path $jkLib -Filter "jk-*.jar" -File -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -ne $jkJar } |
            ForEach-Object {
                try { Remove-Item -LiteralPath $_.FullName -Force } catch { Park-IfPresent $_.FullName }
            }
        try {
            Invoke-NativeLines $script:Java @("--enable-native-access=ALL-UNNAMED", "-jar", $jkJar, "self", "write-launcher") | Out-Null
            $wrote = ($LASTEXITCODE -eq 0)
        } catch {
            $wrote = $false
        }
        if (-not $wrote -or -not (Test-Path -LiteralPath $script:JkBin -PathType Leaf)) {
            Die "could not write $($script:JkBin) (jk self write-launcher failed)"
        }
    } else {
        # Destination name: jk.exe for a native client; a JVM launcher keeps its jk.bat name.
        $leaf = Split-Path -Leaf $ArchiveFile
        $srcExt = [IO.Path]::GetExtension($leaf).ToLowerInvariant()
        if ($srcExt -eq ".bat" -or $leaf -eq "jk.bat") {
            $destName = "jk.bat"
        } elseif ($srcExt -eq ".cmd" -or $leaf -eq "jk.cmd") {
            $destName = "jk.cmd"
        } else {
            $destName = "jk.exe"
        }

        $script:JkBin = Join-Path $InstallDir $destName
        Park-IfPresent $script:JkBin
        # PATHEXT prefers .exe over .bat. Installing the thin client must park a leftover
        # unsigned jk.exe or `jk` still launches the blocked PE.
        if ($destName -eq "jk.bat") {
            Park-IfPresent (Join-Path $InstallDir "jk.exe")
        } elseif ($destName -eq "jk.exe") {
            Park-IfPresent (Join-Path $InstallDir "jk.bat")
        }
        try {
            Expand-JkArchive -Archive $ArchiveFile -Destination $script:JkBin
        } catch {
            Die "failed to install jk: $($_.Exception.Message)"
        }

        # jkx - hardlink to jk.exe (argv[0] dispatch; .exe stripped) when possible.
        # Fallback: jkx.cmd shim (same shape JkxLink writes on Windows).
        Park-IfPresent $jkxExe
        Park-IfPresent $jkxCmd
        $linked = $false
        if ($destName -eq "jk.exe") {
            try {
                New-Item -ItemType HardLink -Path $jkxExe -Target $script:JkBin -Force | Out-Null
                $linked = $true
            } catch {
                $linked = $false
            }
        }
        if (-not $linked) {
            $jkLeaf = Split-Path -Leaf $script:JkBin
            $shim = @"
@echo off
REM jkx - jk tool run launcher (generated by jk; do not edit)
"%~dp0$jkLeaf" tool run %*
"@
            Set-Content -LiteralPath $jkxCmd -Value $shim -Encoding Ascii -Force
        }
    }

    # Clear only resident engines positively identified in the superseded platform default.
    try {
        Invoke-Jk @("self", "retire-old-engines") 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Note "an engine from the superseded install location could not be stopped"
        }
    } catch {
        Write-Note "an engine from the superseded install location could not be stopped"
    }

    # ---- product-lib engine (local dist and JVM installs) --------------------
    #
    # Local dist installs (binary + engine jar together) and JVM installs (the engine jar
    # downloaded and verified above) materialize the engine jar via `jk self materialize`.
    # Native download installs self-fetch on first spawn.

    $engineJar = $null
    if ($IsRemote -and $UseJvm) {
        $engineJar = $remoteEngineJar
    }
    if ($LocalPath) {
        $srcDir = Split-Path -Parent $ArchiveFile
        # build\dist\jk.exe -> build\dist\lib; a JVM client jar sits inside lib\ itself; also
        # try parent\lib for nested layouts.
        foreach ($candidate in @($srcDir, (Join-Path $srcDir "lib"), (Join-Path (Split-Path -Parent $srcDir) "lib"))) {
            if (-not (Test-Path -LiteralPath $candidate)) { continue }
            $engineJar = Get-ChildItem -Path $candidate -Filter "jk-engine-*.jar" -ErrorAction SilentlyContinue |
                Select-Object -First 1 -ExpandProperty FullName
            if ($engineJar) { break }
        }
        if (-not $engineJar -and $env:JK_ENGINE_JAR -and (Test-Path -LiteralPath $env:JK_ENGINE_JAR)) {
            $engineJar = $env:JK_ENGINE_JAR
        }
    }
    if ($engineJar) {
        if ($LocalPath -or $UseJvm) {
            try {
                Invoke-Jk @("self", "materialize", $script:JkBin, $engineJar) 2>&1 | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    Write-Note "engine materialization skipped (jk self materialize failed; the client re-fetches on demand)"
                }
            } catch {
                Write-Note "engine materialization skipped (jk self materialize failed; the client re-fetches on demand)"
            }
            try {
                Invoke-Jk @("self", "setup-terminal") 2>&1 | Out-Null
            } catch {
                Write-Note "terminal setup skipped (run 'jk self setup-terminal' later)"
            }
        }
    }

    # The Maven event spy (`jk mvn` attaches it to Maven's extension path) is a plain jar under
    # the product lib, one per version, copied from the dist beside the engine jar. A download
    # install carries none: the client fetches its own version's spy from the release directory,
    # verified against the same signed sums, on the first `jk mvn`.
    if ($LocalPath -and $engineJar) {
        $spyJars = Get-ChildItem -Path (Split-Path -Parent $engineJar) -Filter "jk-maven-spy-*.jar" -ErrorAction SilentlyContinue
        foreach ($spy in $spyJars) {
            try {
                $productLib = Join-Path $JkHome "lib"
                New-Item -ItemType Directory -Force -Path $productLib | Out-Null
                Copy-Item -LiteralPath $spy.FullName -Destination (Join-Path $productLib $spy.Name) -Force
            } catch {
                Write-Note "could not copy $($spy.Name) into $productLib (jk mvn will run without structured results)"
            }
        }
    }

    # ---- activate ----------------------------------------------------------
    #
    # Writes the installer block into every discovered profile (pwsh + Windows
    # PowerShell 5.1, plus bash/zsh/fish rc files that already exist) and persists the
    # User PATH — for the default home, or on -Rc / JK_RC=1. A private JK_HOME gets the
    # activation line printed instead, and this session's PATH only.
    # Profile scripts need a non-Restricted CurrentUser policy; a blocking policy is
    # reported here (and only changed on request) before the block is written.

    $DefaultHome = Join-Path $HOME ".jk"
    $RcRequested = [bool]$Rc -or $env:JK_RC -eq "1"
    $WriteRc = Resolve-WriteRc $JkHome $DefaultHome $RcRequested
    $ActivateLine = "& `"$($script:JkBin)`" activate pwsh | Out-String | Invoke-Expression"
    if ($WriteRc) {
        Ensure-ProfileExecutionPolicy -Apply ([bool]$SetExecutionPolicy -or $env:JK_SET_EXECUTION_POLICY -eq "1")

        Write-Info "Running ``jk activate --yes``..."
        # `jk activate` applies the same rc rule as this script, so --rc rides along when asked for.
        $activateArgs = @("activate", "--yes")
        if ($RcRequested) { $activateArgs += "--rc" }
        try {
            Invoke-Jk $activateArgs
            if ($LASTEXITCODE -ne 0) {
                Write-Note "'jk activate --yes' failed; run 'jk activate' (or 'jk activate pwsh') manually."
            }
        } catch {
            Write-Note "'jk activate --yes' failed; run 'jk activate' (or 'jk activate pwsh') manually."
        } finally {
            Clear-NativeExitCode
        }

        # Persist User PATH (cmd + PowerShell + GUI apps) and this session's PATH.
        # jk activate still writes $PROFILE for hooks/completions; PATH itself must
        # not depend on a profile loading.
        Ensure-UserPath $InstallDir
    } else {
        Write-Info "JK_HOME is $JkHome, not the default ${DefaultHome}: the profiles and the User PATH are left alone."
        Write-Note "To use this install in the current shell, run: $ActivateLine"
        Write-Note "To write the profile block for this home anyway, install again with -Rc (irm|iex: JK_RC=1)."
        if (-not (Test-PathEntry $env:PATH $InstallDir)) {
            $env:PATH = $InstallDir.TrimEnd('\') + [IO.Path]::PathSeparator + $env:PATH
        }
    }

    # ---- preemptive payload warm-up ----------------------------------------
    #
    # Best-effort: never fail the install. Mirrors install.sh (templates, libraries,
    # jdks.json) under the jk home.

    if (Test-Command "git") {
        $templatesUrl = if ($env:JK_TEMPLATES_URL) { $env:JK_TEMPLATES_URL } else { "https://github.com/JumpKickOSS/jk-templates.git" }
        # Must match JkDirs.templates() = <store>/templates
        $storeTemplates = Join-Path (Get-JkStoreRoot) "templates"
        $tmplKey = ($templatesUrl.ToLowerInvariant() `
            -replace '^https?://', '' `
            -replace '^git@', '' `
            -replace '\.git$', '' `
            -replace '[^a-z0-9._-]', '_')
        $tmplDest = Join-Path $storeTemplates $tmplKey
        try {
            $parent = Split-Path -Parent $tmplDest
            New-Item -ItemType Directory -Force -Path $parent | Out-Null
            if (Test-Path -LiteralPath $tmplDest) {
                Remove-Item -LiteralPath $tmplDest -Recurse -Force -ErrorAction SilentlyContinue
            }
            $prevPrompt = $env:GIT_TERMINAL_PROMPT
            $env:GIT_TERMINAL_PROMPT = "0"
            try {
                Invoke-NativeLines "git" @("clone", "--depth", "1", $templatesUrl, $tmplDest) | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    Write-Note "templates prefetch skipped (git clone failed - will lazy-clone on jk new)"
                }
            } finally {
                if ($null -eq $prevPrompt) { Remove-Item Env:GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue }
                else { $env:GIT_TERMINAL_PROMPT = $prevPrompt }
            }
        } catch {
            Write-Note "templates prefetch skipped (git clone failed - will lazy-clone on jk new)"
        }
    }

    # jk-libraries — one copy at JkDirs.libraryRegistry() = <store>/libs.global.toml
    $storeLibs = Join-Path (Get-JkStoreRoot) "libs.global.toml"
    try {
        $libsUrl = if ($env:JK_LIBRARIES_URL) {
            $env:JK_LIBRARIES_URL
        } else {
            "https://raw.githubusercontent.com/JumpKickOSS/jk-libraries/refs/heads/main/libraries.toml"
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $storeLibs) | Out-Null
        $tmpLibs = "$storeLibs.tmp"
        Save-Url $libsUrl $tmpLibs
        Move-Item -LiteralPath $tmpLibs -Destination $storeLibs -Force
    } catch {
        Remove-Item -LiteralPath "$storeLibs.tmp" -Force -ErrorAction SilentlyContinue
    }

    # jdks.json
    $jdksDest = Join-Path (Get-JkStoreRoot) "jdks.json"
    try {
        $jdksUrl = if ($env:JK_JDKS_URL) { $env:JK_JDKS_URL } else { "https://download.jetbrains.com/jdk/feed/v1/jdks.json" }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $jdksDest) | Out-Null
        $tmpJdks = "$jdksDest.tmp"
        Save-Url $jdksUrl $tmpJdks
        Move-Item -LiteralPath $tmpJdks -Destination $jdksDest -Force
    } catch {
        Remove-Item -LiteralPath "$jdksDest.tmp" -Force -ErrorAction SilentlyContinue
    }

    # ---- warm the engine ---------------------------------------------------
    if (-not $SkipEngineWarm -and ((-not $LocalPath) -or $engineJar)) {
        # Local dogfood reinstalls keep the same version string while replacing the engine jar. A
        # still-running engine would keep serving the old one, and there is nothing to stop on a
        # first install — so a failure here is expected, not reportable.
        try { Invoke-Jk @("engine", "stop", "--force") 2>&1 | Out-Null } catch { }
        # The start is what installs the engine's JDK on a machine that has none, and the client
        # renders that download as the `jk jdk install` progress bar — so it runs on the user's
        # console, not into Out-Null, where a two-minute download looks like a hang.
        Write-Info "Starting the build engine... This may download a JDK and optimize your installation"
        try {
            Invoke-Jk @("engine", "start")
            if ($LASTEXITCODE -ne 0) {
                Write-Note "Engine warm-up skipped; it will start on first build"
            }
        } catch {
            Write-Note "Engine warm-up skipped; it will start on first build"
        }
    }

    Remove-Item -LiteralPath "$($script:JkBin).old" -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath "$jkxExe.old" -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath "$jkxCmd.old" -Force -ErrorAction SilentlyContinue

    Write-Host ""
    Write-Info "JumpKick is ready! Hi-ya!"
    if ($WriteRc) {
        Write-Host ("{0}{1}{2} Open a new terminal (cmd or PowerShell) to start using {3}jk{4}" -f `
            $script:GREEN, $script:DOT, $script:RESET, $script:YELLOW, $script:RESET)
        Write-Note "Install dir: $InstallDir (on your User PATH)"
        Write-Note "Hooks/completions: . `$PROFILE  (after jk activate)"
    } else {
        Write-Host ("{0}{1}{2} Run {3}{5}{4} to start using {3}jk{4} in this shell" -f `
            $script:GREEN, $script:DOT, $script:RESET, $script:YELLOW, $script:RESET, $ActivateLine)
        Write-Note "Install dir: $InstallDir (this session's PATH only)"
    }
    Write-Host ""
    Clear-NativeExitCode
}
finally {
    Remove-Item -LiteralPath $tmpRoot -Recurse -Force -ErrorAction SilentlyContinue
}
