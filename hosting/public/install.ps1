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
#   JK_ARCHIVE_URL   Override the archive URL to download (.zip preferred; plain .exe URL also works).
#   JK_RELEASES_URL  Override the release site root (mirrors).
#   JK_VERSION       Install a specific version instead of the latest.
#   JK_INSTALL_DIR   Override the install directory (default: %USERPROFILE%\.local\bin,
#                    or $JK_BIN_DIR / $JK_HOME\bin when set).
#   JK_BIN_DIR       Same as JK_INSTALL_DIR (product layout env).
#   JK_HOME          Optional single-tree umbrella; mirrors the XDG layout
#                    ($JK_HOME\{bin,cache,config,data,state}). (tests/CI)
#   JK_NONINTERACTIVE / CI  Force non-interactive path.
#   JK_LOCAL_PATH    Local binary/archive when invoking via irm|iex (no positional args).
#
# On-disk layout (JkDirs): PATH entrypoints live under %USERPROFILE%\.local\bin so wiping
# product data does not uninstall the CLI. Data/cache/state under
# %LOCALAPPDATA%\jk\{data,cache,state}; config under %APPDATA%\jk; the store is <data>\store.
# See docs/user/install.md.
#
# Requires Windows PowerShell 5.1+ or PowerShell 7+.

#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $LocalPath = "",

    # Skip engine warm-up (CI / PATH-only install).
    [switch] $SkipEngineWarm
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
# PATH entrypoints live outside product data (uv-style). Default matches JkDirs:
# %USERPROFILE%\.local\bin

if ($env:JK_INSTALL_DIR) {
    $InstallDir = $env:JK_INSTALL_DIR
} elseif ($env:JK_BIN_DIR) {
    $InstallDir = $env:JK_BIN_DIR
} elseif ($env:JK_HOME) {
    # Under the umbrella, bin is one of the five roots. `jk activate` writes
    # $JK_HOME\bin\jk into the profile and `jk self update` replaces the binary there,
    # so installing anywhere else leaves both pointing at nothing.
    $InstallDir = Join-Path $env:JK_HOME "bin"
} else {
    $InstallDir = Join-Path $HOME ".local\bin"
}

$ReleasesUrl = if ($env:JK_RELEASES_URL) { $env:JK_RELEASES_URL.TrimEnd("/") } else { "https://jumpkick.build/releases" }

# irm|iex cannot pass positional args; allow JK_LOCAL_PATH as the local-dist seam.
if (-not $LocalPath -and $env:JK_LOCAL_PATH) {
    $LocalPath = $env:JK_LOCAL_PATH
}

# ---- helpers ---------------------------------------------------------------

function Test-Command([string] $Name) {
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Get-JkTarget {
    $os = "windows"
    $archName = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
    switch -Regex ($archName) {
        "^(X64|Amd64)$" { $arch = "x86_64" }
        "^(Arm64)$" { $arch = "aarch64" }
        default {
            # Fallback for older hosts / unusual report strings.
            $procArch = $env:PROCESSOR_ARCHITECTURE
            if ($procArch -match "(?i)ARM64") { $arch = "aarch64" }
            elseif ($procArch -match "(?i)AMD64|X86") { $arch = "x86_64" }
            else { Die "unsupported architecture: $archName (supported: x86_64, aarch64)" }
        }
    }
    return "$os-$arch"
}

function Get-TextUrl([string] $Url) {
    # PS 5.1 may return a byte[] for some content types; normalize to string.
    $resp = Invoke-WebRequest -UseBasicParsing -Uri $Url
    if ($resp.Content -is [byte[]]) {
        return [Text.Encoding]::UTF8.GetString($resp.Content).Trim()
    }
    return ([string]$resp.Content).Trim()
}

function Save-Url([string] $Url, [string] $OutFile) {
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $OutFile
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

# Root resolution mirrors JkDirs: a role-specific JK_*_DIR wins, else $JK_HOME\<root>,
# else the Windows Known Folder default.

function Get-JkLocalAppData {
    if ($env:LOCALAPPDATA) { return $env:LOCALAPPDATA }
    return (Join-Path $HOME "AppData\Local")
}

# JK_HOME mirrors the XDG shape, so data is a root and the store hangs off it.
function Get-JkDataRoot {
    if ($env:JK_DATA_DIR) { return $env:JK_DATA_DIR }
    if ($env:JK_HOME)     { return (Join-Path $env:JK_HOME "data") }
    return (Join-Path (Get-JkLocalAppData) "jk\data")
}

function Get-JkStoreRoot {
    if ($env:JK_STORE_DIR) { return $env:JK_STORE_DIR }
    return (Join-Path (Get-JkDataRoot) "store")
}

function Invoke-Jk {
    param([Parameter(Mandatory = $true)][string[]] $JkArgs)
    # irm|iex already has the script in memory (unlike curl|bash), but still avoid
    # interactive prompts hanging on a non-console stdin.
    & $script:JkBin @JkArgs
    return $LASTEXITCODE
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
    $ArchiveUrl = "$ReleasesUrl/$version/jk-$target.zip"
    $IsRemote = $true
}

# ---- download & install ----------------------------------------------------

$tmpRoot = Join-Path ([IO.Path]::GetTempPath()) ("jk-install-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null
try {
    if ($IsRemote) {
        $ArchiveFile = Join-Path $tmpRoot "jk.archive"
        # Preserve a useful extension for decompress dispatch.
        $urlExt = [IO.Path]::GetExtension(($ArchiveUrl -split "\?")[0])
        if ($urlExt) { $ArchiveFile = $ArchiveFile + $urlExt }
        Write-Info "Downloading $ArchiveUrl"
        try {
            Save-Url $ArchiveUrl $ArchiveFile
        } catch {
            Die "failed to download $ArchiveUrl ($($_.Exception.Message))"
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
    # Shell.detect() reads $SHELL; Windows shells often leave it unset. Force pwsh
    # so `jk activate --yes` can write the PowerShell profile marker block.

    Write-Info "Running ``jk activate --yes``... This may download a JDK and optimize your installation"
    $prevShell = $env:SHELL
    $env:SHELL = "pwsh"
    try {
        Invoke-Jk @("activate", "--yes")
        if ($LASTEXITCODE -ne 0) {
            Write-Note "'jk activate --yes' failed; run 'jk activate' (or 'jk activate pwsh') manually."
        }
    } catch {
        Write-Note "'jk activate --yes' failed; run 'jk activate' (or 'jk activate pwsh') manually."
    } finally {
        if ($null -eq $prevShell) { Remove-Item Env:SHELL -ErrorAction SilentlyContinue }
        else { $env:SHELL = $prevShell }
        Clear-NativeExitCode
    }

    # Persist User PATH (cmd + PowerShell + GUI apps) and this session's PATH.
    # jk activate still writes $PROFILE for hooks/completions; PATH itself must
    # not depend on a profile loading.
    Ensure-UserPath $InstallDir

    # ---- preemptive payload warm-up ----------------------------------------
    #
    # Best-effort: never fail the install. Mirrors install.sh (templates, libraries,
    # jdks.json) with Windows Known Folder paths.

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
