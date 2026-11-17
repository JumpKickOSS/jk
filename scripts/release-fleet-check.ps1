# After a release is published: put this Windows machine on it from nothing and time it. Stops
# every engine, DELETES %USERPROFILE%\.jk, installs with the published `irm install.ps1 | iex`,
# brings the jk checkout to the released commit and builds it twice (cold, then warm).
# Windows PowerShell 5.1 or later.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File release-fleet-check.ps1 -Version <version> [-Checkout <dir>]
#   powershell -NoProfile -ExecutionPolicy Bypass -File release-fleet-check.ps1 -RemoveOnly <dir>
#   powershell -NoProfile -ExecutionPolicy Bypass -File release-fleet-check.ps1 -Version <version> -CheckoutOnly <dir>
# -RemoveOnly runs the home's delete step alone against <dir> (what scripts/test-release-fleet-check.ps1
# drives) and exits 0 when <dir> is gone. -CheckoutOnly runs the checkout step alone against <dir>:
# prints "usable" and exits 0 when <dir> was brought to tag v<version>, else prints why not.
#
# The home goes in an order that never leaves a home with no jk in it: every process running from
# it or naming it on its command line is stopped and waited for (a worker JVM, a git a test left
# behind), everything but bin is deleted, and bin goes last. When a file will not go, bin stays, so
# the profile's activate line and `jk` keep working, and the failure names the process holding
# each file (Windows Restart Manager).
# -Checkout defaults to %USERPROFILE%\src\oss\jk. It is fast-forwarded to tag v<version> only when it
# is on main, clean, and holds no commit origin lacks; otherwise it is left alone, reported, and
# %USERPROFILE%\src\oss\jk-fleet is used instead (under the same rule).
# Refuses to run unless https://jumpkick.build/releases/latest/VERSION is <version>, and refuses a
# JK_HOME other than %USERPROFILE%\.jk.
#
# Prints progress, then one line:
#   RESULT host=… os=… cores=… ram_gb=… jk=… commit=… install_s=… build1_s=… build2_s=… checkout=… note=… success=true|false
param(
    [string] $Version,
    [string] $Checkout = (Join-Path $env:USERPROFILE "src\oss\jk"),
    [string] $RemoveOnly,
    [string] $CheckoutOnly
)
$ErrorActionPreference = "Continue"

# The processes holding any of $files open, as "name (pid N): file" lines, from the Restart Manager:
# the one Windows API that answers which process has a given file open.
function Get-FileHolders([string[]] $files) {
    if (-not $files) { return @() }
    if (-not ("JkFleet.Rm" -as [type])) {
        Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
namespace JkFleet {
  public static class Rm {
    [StructLayout(LayoutKind.Sequential)] struct UniqueProcess { public int Pid; public System.Runtime.InteropServices.ComTypes.FILETIME Start; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct ProcessInfo {
      public UniqueProcess Process;
      [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string AppName;
      [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string ServiceName;
      public int AppType; public uint AppStatus; public uint TsSessionId; [MarshalAs(UnmanagedType.Bool)] public bool Restartable;
    }
    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)] static extern int RmStartSession(out uint h, int flags, string key);
    [DllImport("rstrtmgr.dll")] static extern int RmEndSession(uint h);
    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)] static extern int RmRegisterResources(uint h, uint nFiles, string[] files, uint nApps, IntPtr apps, uint nSvc, string[] svcs);
    [DllImport("rstrtmgr.dll")] static extern int RmGetList(uint h, out uint needed, ref uint n, [In, Out] ProcessInfo[] info, ref uint reasons);
    public static List<string> Holders(string file) {
      var found = new List<string>();
      uint h; if (RmStartSession(out h, 0, Guid.NewGuid().ToString()) != 0) return found;
      try {
        if (RmRegisterResources(h, 1, new[] { file }, 0, IntPtr.Zero, 0, null) != 0) return found;
        uint needed = 0, n = 0, reasons = 0;
        int rc = RmGetList(h, out needed, ref n, null, ref reasons);
        if (rc == 234 && needed > 0) {
          var info = new ProcessInfo[needed]; n = needed;
          if (RmGetList(h, out needed, ref n, info, ref reasons) == 0)
            for (int i = 0; i < n; i++) found.Add(info[i].AppName + " (pid " + info[i].Process.Pid + ")");
        }
      } finally { RmEndSession(h); }
      return found;
    }
  }
}
"@
    }
    $lines = @()
    foreach ($f in $files) { foreach ($p in [JkFleet.Rm]::Holders($f)) { $lines += "${p}: $f" } }
    return $lines
}

# Stop every process that runs from $dir or names it on its command line, and wait for each to exit.
function Stop-ProcessesUnder([string] $dir) {
    $self = $PID
    $parents = @()
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$self"
    while ($p -and $p.ParentProcessId -and $parents.Count -lt 16) {
        $parents += [int]$p.ParentProcessId
        $p = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.ParentProcessId)"
    }
    $named = $dir.TrimEnd('\')
    $prefix = $named + '\'
    $hits = Get-CimInstance Win32_Process | Where-Object {
        $_.ProcessId -ne $self -and $parents -notcontains [int]$_.ProcessId -and (
            ($_.ExecutablePath -and $_.ExecutablePath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) -or
            ($_.CommandLine -and ($_.CommandLine.IndexOf($named, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                $_.CommandLine.IndexOf($named.Replace('\', '/'), [StringComparison]::OrdinalIgnoreCase) -ge 0)))
    }
    foreach ($h in $hits) {
        Write-Host "fleet-check: stopping $($h.Name) (pid $($h.ProcessId)), which runs from or names $dir"
        Stop-Process -Id $h.ProcessId -Force -ErrorAction SilentlyContinue
    }
    foreach ($h in $hits) { Wait-Process -Id $h.ProcessId -Timeout 15 -ErrorAction SilentlyContinue }
}

# $path in its \\?\ form, which Windows PowerShell 5.1 needs to reach past MAX_PATH: the template
# store's g8 trees nest deep enough under a home that Remove-Item without it fails with "Could not
# find a part of the path", with no process holding anything.
function LongPath([string] $path) {
    if ($path.StartsWith('\\?\')) { return $path }
    return '\\?\' + [IO.Path]::GetFullPath($path)
}
function ShortPath([string] $path) { if ($path.StartsWith('\\?\')) { return $path.Substring(4) } else { return $path } }

# Delete $dir with bin last. Returns "" when $dir is gone, else why not (bin then still stands).
function Remove-JkHome([string] $dir) {
    $long = LongPath $dir
    if (-not (Test-Path -LiteralPath $long)) { return "" }
    Stop-ProcessesUnder (ShortPath $long)
    $bin = $long.TrimEnd('\') + '\bin'   # Join-Path refuses the \\?\ form
    $rest = { Get-ChildItem -LiteralPath $long -Force | Where-Object { $_.FullName -ne $bin } }
    # A file a process just let go of can stay locked for a moment (an antivirus scan, a closing handle).
    for ($try = 0; $try -lt 5; $try++) {
        & $rest | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
        if (-not (& $rest)) { break }
        Start-Sleep -Milliseconds 500
    }
    if (& $rest) {
        $left = @(& $rest | ForEach-Object {
                if ($_.PSIsContainer) { Get-ChildItem -LiteralPath $_.FullName -Force -Recurse -File -ErrorAction SilentlyContinue } else { $_ }
            } | Select-Object -First 50 | ForEach-Object { ShortPath $_.FullName })
        if (-not $left) { $left = @(& $rest | ForEach-Object { ShortPath $_.FullName }) }
        $holders = Get-FileHolders $left
        foreach ($l in $holders) { Write-Host "fleet-check: held: $l" }
        $what = if ($holders) { ($holders | Select-Object -First 3) -join "; " } else { "$($left.Count) entries left, holder unknown; first $($left[0])" }
        return "could not delete $(ShortPath $long), bin kept ($what)"
    }
    for ($try = 0; $try -lt 5 -and (Test-Path -LiteralPath $long); $try++) {
        Remove-Item -LiteralPath $long -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $long) { Start-Sleep -Milliseconds 500 }
    }
    if (Test-Path -LiteralPath $long) {
        $left = @(Get-ChildItem -LiteralPath $long -Force -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { ShortPath $_.FullName })
        $holders = Get-FileHolders $left
        foreach ($l in $holders) { Write-Host "fleet-check: held: $l" }
        return "could not delete $(ShortPath $bin) ($(($holders | Select-Object -First 3) -join '; '))"
    }
    return ""
}

# Whether $dir can be brought to tag v$Version: on main, clean, nothing origin lacks; then
# fast-forwarded. Only main and the release tag are fetched: a local tag that origin moved or dropped
# is no reason to pass over the checkout (a plain fetch --tags fails on it with "would clobber
# existing tag").
function Usable([string] $dir) {
    if (-not (Test-Path (Join-Path $dir ".git"))) { return "absent" }
    git -C $dir fetch -q --no-tags origin "+refs/heads/main:refs/remotes/origin/main" "+refs/tags/v${Version}:refs/tags/v$Version"
    if ($LASTEXITCODE -ne 0) { return "fetch-failed" }
    $branch = (git -C $dir symbolic-ref --short -q HEAD); if (-not $branch) { $branch = "detached" }
    if ($branch -ne "main") { return "on-$branch" }
    if (git -C $dir status --porcelain) { return "dirty" }
    if (git -C $dir log --oneline origin/main..main) { return "unpushed-commits" }
    git -C $dir merge -q --ff-only "v$Version"; if ($LASTEXITCODE -ne 0) { return "not-fast-forward" }
    return ""
}

if ($RemoveOnly) {
    $why = Remove-JkHome $RemoveOnly
    if ($why) { Write-Host "fleet-check: $why"; exit 1 }
    exit 0
}
if (-not $Version) { Write-Host "fleet-check: -Version <version> is required"; exit 2 }
if ($CheckoutOnly) {
    $why = Usable $CheckoutOnly
    if ($why) { Write-Host $why; exit 1 }
    Write-Host "usable"; exit 0
}
$Site = if ($env:JK_FLEET_SITE) { $env:JK_FLEET_SITE } else { "https://jumpkick.build" }
$HomeDir = Join-Path $env:USERPROFILE ".jk"
$Jk = Join-Path $HomeDir "bin\jk.exe"

$r = [ordered]@{
    host = $env:COMPUTERNAME
    os = "Windows-" + [Environment]::OSVersion.Version.ToString() + "-" + $env:PROCESSOR_ARCHITECTURE
    cores = [Environment]::ProcessorCount
    ram_gb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
    jk = "none"; commit = "none"; install_s = "-"; build1_s = "-"; build2_s = "-"; checkout = "none"; note = "none"; success = "false"
}
function Result { "RESULT " + (($r.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join " ") }
function Fail([string] $why) {
    $n = ($why -replace '\s', '_')
    $r.note = if ($r.note -ne "none" -and -not $why.StartsWith($r.note)) { "$($r.note),$n" } else { $n }
    Write-Host "fleet-check: $why"; Result; exit 1
}
function Secs([System.Diagnostics.Stopwatch] $w) { "{0:F1}" -f $w.Elapsed.TotalSeconds }

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
try { $published = (Invoke-RestMethod "$Site/releases/latest/VERSION").ToString().Trim() } catch { $published = "unreadable" }
if ($published -ne $Version) { Fail "refusing: $Site/releases/latest/VERSION is '$published', not $Version" }
if ($env:JK_HOME -and ($env:JK_HOME -ne $HomeDir)) { Fail "refusing: JK_HOME is $env:JK_HOME, not $HomeDir" }

# 0. every engine of this home stops
if (Test-Path $Jk) { & $Jk engine stop -A --no-ansi; if ($LASTEXITCODE -ne 0) { Write-Host "fleet-check: engine stop -A failed; continuing" } }
# 1. the home goes, bin last, after every process under it has stopped
$why = Remove-JkHome $HomeDir
if ($why) { Fail $why }

# 2. the published installer, as a user runs it
$env:JK_NONINTERACTIVE = "1"
$w = [Diagnostics.Stopwatch]::StartNew()
# In a child process: the installer exits on its own errors, which would end this script too.
& powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Invoke-RestMethod '$Site/install.ps1' | Invoke-Expression"
if ($LASTEXITCODE -ne 0) { Fail "the published installer failed (exit $LASTEXITCODE)" }
$r.install_s = Secs $w
if (-not (Test-Path $Jk)) { Fail "the installer left no $Jk" }
$vv = (& $Jk --version -v --no-ansi 2>&1 | Out-String)
if ($vv -match '(?m)^jk (\S+)') { $r.jk = $Matches[1] }
if ($vv -match 'client .* commit ([0-9a-f]+)') { $r.commit = $Matches[1] }
if ($r.jk -ne $Version) { Fail "the installed jk reports '$($r.jk)', not $Version" }

# 3. the checkout at the released commit, or the fleet checkout when the usual one is someone's work
$why = Usable $Checkout
if ($why -eq "") { $r.checkout = $Checkout } else {
    $r.note = "${Checkout}:$why" -replace '\s', '_'
    $fleet = Join-Path $env:USERPROFILE "src\oss\jk-fleet"
    Write-Host "fleet-check: $Checkout left alone ($why); using $fleet"
    $why2 = Usable $fleet
    if ($why2 -ne "") { Fail "$($r.note),jk-fleet:$why2" }
    $r.checkout = $fleet
}

# 4. two builds of the released tree with the released jk
Set-Location $r.checkout
$w = [Diagnostics.Stopwatch]::StartNew()
& $Jk build --no-ansi
$r.build1_s = Secs $w
if ($LASTEXITCODE -ne 0) { Fail "first build failed" }
$w = [Diagnostics.Stopwatch]::StartNew()
& $Jk build --no-ansi
$r.build2_s = Secs $w
if ($LASTEXITCODE -ne 0) { Fail "second build failed" }
$r.success = "true"
Result
