# jk installer (Windows / PowerShell)
#
# Usage:
#   irm https://jumpkick.build/install.ps1 | iex
#   powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://jumpkick.build/install.ps1 | iex"
#   .\install.cmd [path\to\jk.exe|.zip]          # recommended locally (bypasses Restricted policy)
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 [path\to\jk.exe|.zip]
#   pwsh -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 path\to\jk.exe
#
# Environment variables:
#   JK_ARCHIVE_URL   Override the archive URL. JK_VERSION is required; signed evidence
#                    still comes from JK_RELEASES_URL\<version>\.
#   JK_RELEASES_URL  Override the release site root (mirrors).
#   JK_VERSION       Install a specific version instead of the latest.
#   JK_HOME          jk's home directory. Default %USERPROFILE%\.jk; everything jk owns lives
#                    under it. The client is installed to $JK_HOME\bin.
#   JK_NONINTERACTIVE / CI  Force non-interactive path.
#   JK_LOCAL_PATH    Local binary/archive when invoking via irm|iex (no positional args).
#   JK_SET_EXECUTION_POLICY=1  Apply `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` when
#                    the current policy would block profile hooks (same as -SetExecutionPolicy).
#                    By default the installer only prints that command.
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

    # Persist a CurrentUser RemoteSigned execution policy when the effective one would block
    # profile hooks. Off by default: the installer prints the command instead of changing a
    # setting no uninstall reverts. irm|iex callers use JK_SET_EXECUTION_POLICY=1.
    [switch] $SetExecutionPolicy,

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
$ReleaseRsaModulus = "ztiftv1t1l9vI1xebPHGe/MAapolNPiYE/elvRFT2OL3SyawfN19L+qxiyHGsJUF52+zGhVLU2s5RR+b5bj9Cjey2wDs+AAL4nR0FSzo8seXNoahOMZfv+gJY386YenXGAxbElwuw3LqTIlfQvPwiX+9m/RBltKk7WOQI9z35/a17P1i7sj8hC/QHtRCnhsX73wGFKP9jng1Ftk+v/U5gvzSOcGawYyQJ0iP/p2nyiBIrxTLSJDx4u+gHVdk1PUmW8p5h31GsDqBUzkTX0GUut2gVolaPpW/9rP/QyNv9vtxtnby6T1xVSAkP5rL+rIedr52mSwiUDOTl9WxJzGZdIREvSMn6H37pAFATbokYETCEQQ33MelCWFMjfQYBxSU0dVrigmiQOYIhYOR9IcuFM22w5Lkq2jick7u/TKZGy9Nq0F2/jxNF28CQj7S5nkpoQTrHIfg86/upXMtU3QK/Zfes37TGptB3wuPjXm3b09iiquOqrClJ6TN9Yz1tbu7"
$ReleaseRsaExponent = "AQAB"

# irm|iex cannot pass positional args; allow JK_LOCAL_PATH as the local-dist seam.
if (-not $LocalPath -and $env:JK_LOCAL_PATH) {
    $LocalPath = $env:JK_LOCAL_PATH
}

# ---- helpers ---------------------------------------------------------------

function Test-Command([string] $Name) {
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Get-JkTarget {
    # Releases publish windows-x86_64 only. Windows on ARM runs that build under x64 emulation,
    # so an ARM64 host installs it and is told so rather than asking for an artifact that does
    # not exist. The inputs are parameters so the mapping is testable off the host.
    param(
        [string] $ArchName = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString(),
        # Fallback for older hosts / unusual report strings.
        [string] $ProcessorArchitecture = $env:PROCESSOR_ARCHITECTURE
    )
    $arm64 = ($ArchName -match "^Arm64$") -or
        ($ArchName -notmatch "^(X64|Amd64)$" -and $ProcessorArchitecture -match "(?i)ARM64")
    $x64 = ($ArchName -match "^(X64|Amd64)$") -or ($ProcessorArchitecture -match "(?i)AMD64|X86")
    if ($arm64) {
        Write-Note "Windows on ARM64: no windows-aarch64 release exists yet; installing the windows-x86_64 build (runs under x64 emulation)."
    } elseif (-not $x64) {
        Die "unsupported architecture: $ArchName (supported: x86_64; ARM64 installs the x86_64 build)"
    }
    return "windows-x86_64"
}

# Windows PowerShell 5.1 repaints its progress bar on every received chunk, which makes a
# multi-megabyte Invoke-WebRequest many times slower. The preference is set in the function
# scope, so the caller's session keeps its own value.

function Get-TextUrl([string] $Url) {
    $ProgressPreference = "SilentlyContinue"
    # PS 5.1 may return a byte[] for some content types; normalize to string.
    $resp = Invoke-WebRequest -UseBasicParsing -Uri $Url
    if ($resp.Content -is [byte[]]) {
        return [Text.Encoding]::UTF8.GetString($resp.Content).Trim()
    }
    return ([string]$resp.Content).Trim()
}

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
    $signatureBytes = [IO.File]::ReadAllBytes($Signature)
    $signatureText = [Text.Encoding]::ASCII.GetString($signatureBytes)
    if ($signatureText -notmatch '^[A-Za-z0-9+/]+={0,2}\r?\n?$') {
        throw "release signature is malformed"
    }
    try {
        $signatureValue = [Convert]::FromBase64String($signatureText.TrimEnd([char[]]"`r`n"))
    } catch {
        throw "release signature is not valid base64"
    }
    if ($signatureValue.Length -ne ([Convert]::FromBase64String($Modulus)).Length) {
        throw "release signature has the wrong RSA length"
    }

    $parameters = New-Object System.Security.Cryptography.RSAParameters
    $parameters.Modulus = [Convert]::FromBase64String($Modulus)
    $parameters.Exponent = [Convert]::FromBase64String($Exponent)
    $rsa = [Security.Cryptography.RSA]::Create()
    try {
        $rsa.ImportParameters($parameters)
        $valid = $rsa.VerifyData(
            $manifestBytes,
            $signatureValue,
            [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    } finally {
        $rsa.Dispose()
    }
    if (-not $valid) {
        throw "release signature verification failed; refusing the download"
    }

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

$ArchiveUrl = $null
$ArchiveFile = $null
$IsRemote = $false

if ($LocalPath) {
    if (-not (Test-Path -LiteralPath $LocalPath -PathType Leaf)) {
        Die "local file not found: $LocalPath"
    }
    $ArchiveFile = (Resolve-Path -LiteralPath $LocalPath).Path
} elseif ($env:JK_ARCHIVE_URL) {
    if (-not $env:JK_VERSION) {
        Die "JK_VERSION is required when JK_ARCHIVE_URL is set."
    }
    $version = $env:JK_VERSION
    $ArchiveUrl = $env:JK_ARCHIVE_URL
    $IsRemote = $true
} else {
    $target = Get-JkTarget
    $version = $env:JK_VERSION
    if (-not $version) {
        try {
            $version = Get-TextUrl "$ReleasesUrl/latest/VERSION"
        } catch {
            Die "could not resolve the latest jk version from $ReleasesUrl/latest/VERSION ($($_.Exception.Message))"
        }
    }
    if (-not $version) {
        Die "could not resolve the latest jk version from $ReleasesUrl/latest/VERSION"
    }
    # Windows installer prefers .zip (no system xz). Self-update uses .xz via the engine.
    $ArchiveUrl = "$ReleasesUrl/$version/jk-$target-$version.zip"
    $IsRemote = $true
}

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

$tmpRoot = Join-Path ([IO.Path]::GetTempPath()) ("jk-install-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null
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
            Save-Url "$ReleaseVersionUrl/SHA256SUMS" $manifestFile
            Save-Url "$ReleaseVersionUrl/SHA256SUMS.sig" $signatureFile
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
    }

    $displayDir = $InstallDir
    $homePrefix = $HOME
    if ($InstallDir.StartsWith($homePrefix, [StringComparison]::OrdinalIgnoreCase)) {
        $displayDir = "~" + $InstallDir.Substring($homePrefix.Length)
    }

    Write-Host ""
    Write-Info "Installing JumpKick into $displayDir"
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

    # Destination name: prefer jk.exe for native; keep jk.bat for Gradle installDist.
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
    $jkxExe = Join-Path $InstallDir "jkx.exe"
    $jkxCmd = Join-Path $InstallDir "jkx.cmd"
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

    # Clear only resident engines positively identified in the superseded platform default.
    try {
        Invoke-Jk @("self", "retire-old-engines") 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Note "an engine from the superseded install location could not be stopped"
        }
    } catch {
        Write-Note "an engine from the superseded install location could not be stopped"
    }

    # ---- product-lib engine (local dist only) ------------------------------
    #
    # Local dist installs (binary + engine jar together) materialize the engine
    # jar via `jk self materialize`. Download installs self-fetch on first spawn.

    $engineJar = $null
    if ($LocalPath) {
        $srcDir = Split-Path -Parent $ArchiveFile
        $libDir = Join-Path $srcDir "lib"
        if (-not (Test-Path -LiteralPath $libDir)) {
            # build/dist/jk.exe -> build/dist/lib; also try parent\lib for nested layouts.
            $libDir = Join-Path (Split-Path -Parent $srcDir) "lib"
        }
        if (Test-Path -LiteralPath $libDir) {
            $engineJar = Get-ChildItem -Path $libDir -Filter "jk-engine-*.jar" -ErrorAction SilentlyContinue |
                Select-Object -First 1 -ExpandProperty FullName
        }
        if (-not $engineJar -and $env:JK_ENGINE_JAR -and (Test-Path -LiteralPath $env:JK_ENGINE_JAR)) {
            $engineJar = $env:JK_ENGINE_JAR
        }
        if ($engineJar) {
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

    # ---- activate ----------------------------------------------------------
    #
    # Writes the installer block into every discovered profile (pwsh + Windows
    # PowerShell 5.1, plus bash/zsh/fish rc files that already exist).
    # Profile scripts need a non-Restricted CurrentUser policy; a blocking policy is
    # reported here (and only changed on request) before the block is written.

    Ensure-ProfileExecutionPolicy -Apply ([bool]$SetExecutionPolicy -or $env:JK_SET_EXECUTION_POLICY -eq "1")

    Write-Info "Running ``jk activate --yes``... This may download a JDK and optimize your installation"
    try {
        Invoke-Jk @("activate", "--yes")
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
                & git clone --depth 1 $templatesUrl $tmplDest 2>&1 | Out-Null
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
        try {
            Invoke-Jk @("engine", "start") 2>&1 | Out-Null
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
    Write-Host ("{0}{1}{2} Open a new terminal (cmd or PowerShell) to start using {3}jk{4}" -f `
        $script:GREEN, $script:DOT, $script:RESET, $script:YELLOW, $script:RESET)
    Write-Note "Install dir: $InstallDir (on your User PATH)"
    Write-Note "Hooks/completions: . `$PROFILE  (after jk activate)"
    Write-Host ""
    Clear-NativeExitCode
}
finally {
    Remove-Item -LiteralPath $tmpRoot -Recurse -Force -ErrorAction SilentlyContinue
}
