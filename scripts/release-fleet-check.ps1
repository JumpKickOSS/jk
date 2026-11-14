# After a release is published: put this Windows machine on it from nothing and time it. Stops
# every engine, DELETES %USERPROFILE%\.jk, installs with the published `irm install.ps1 | iex`,
# brings the jk checkout to the released commit and builds it twice (cold, then warm).
# Windows PowerShell 5.1 or later.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File release-fleet-check.ps1 -Version <version> [-Checkout <dir>]
# -Checkout defaults to %USERPROFILE%\src\oss\jk. It is fast-forwarded to tag v<version> only when it
# is on main, clean, and holds no commit origin lacks; otherwise it is left alone, reported, and
# %USERPROFILE%\src\oss\jk-fleet is used instead (under the same rule).
# Refuses to run unless https://jumpkick.build/releases/latest/VERSION is <version>, and refuses a
# JK_HOME other than %USERPROFILE%\.jk.
#
# Prints progress, then one line:
#   RESULT host=… os=… cores=… ram_gb=… jk=… commit=… install_s=… build1_s=… build2_s=… checkout=… note=… success=true|false
param(
    [Parameter(Mandatory = $true)][string] $Version,
    [string] $Checkout = (Join-Path $env:USERPROFILE "src\oss\jk")
)
$ErrorActionPreference = "Continue"
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
# 1. the home goes
if (Test-Path $HomeDir) { Remove-Item -LiteralPath $HomeDir -Recurse -Force -ErrorAction SilentlyContinue }
if (Test-Path $HomeDir) { Fail "could not delete $HomeDir (a process still holds a file under it)" }

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
function Usable([string] $dir) {
    if (-not (Test-Path (Join-Path $dir ".git"))) { return "absent" }
    git -C $dir fetch -q --tags origin; if ($LASTEXITCODE -ne 0) { return "fetch-failed" }
    $branch = (git -C $dir symbolic-ref --short -q HEAD); if (-not $branch) { $branch = "detached" }
    if ($branch -ne "main") { return "on-$branch" }
    if (git -C $dir status --porcelain) { return "dirty" }
    if (git -C $dir log --oneline origin/main..main) { return "unpushed-commits" }
    git -C $dir merge -q --ff-only "v$Version"; if ($LASTEXITCODE -ne 0) { return "not-fast-forward" }
    return ""
}
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
