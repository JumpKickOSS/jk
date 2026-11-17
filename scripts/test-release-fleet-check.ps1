# Fixture test for release-fleet-check.ps1's home delete (-RemoveOnly), on Windows:
#  1. a process that names the home on its command line and holds a file under it is stopped, and
#     the home goes;
#  2. a home whose store nests past MAX_PATH (as the template store's g8 trees do) goes;
#  3. a process that holds a file under the home without naming it is not stopped: the delete
#     fails naming that process and the file, everything but bin is gone, and bin is still there;
#     once that process exits, the next run deletes the home;
#  4. the checkout step (-CheckoutOnly) brings a clean checkout on main to the release tag even when
#     it holds a local tag origin moved, which a plain `git fetch --tags` refuses.
# Windows PowerShell 5.1 or later. Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test-release-fleet-check.ps1
$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "release-fleet-check.ps1"
$root = Join-Path ([IO.Path]::GetTempPath()) ("jk-fleet-test-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
$failures = 0
function Check([bool] $ok, [string] $what) {
    if ($ok) { Write-Host "ok   $what" } else { Write-Host "FAIL $what"; $script:failures++ }
}
function New-Home([string] $name) {
    $h = Join-Path $root $name
    foreach ($d in "bin", "lib\jk-engine", "state\engine", "test-homes\slot\tmp") { New-Item -ItemType Directory -Force (Join-Path $h $d) | Out-Null }
    Set-Content (Join-Path $h "bin\jk.cmd") "@echo jk"
    Set-Content (Join-Path $h "lib\jk-engine\jk-engine.jar") "jar"
    return $h
}
# A PowerShell that opens $file with no sharing and sleeps; $encoded hides the path from its command line.
function Start-Holder([string] $file, [bool] $encoded) {
    $code = "`$f = [IO.File]::Open('$file', 'Create', 'ReadWrite', 'None'); Set-Content '$file.ready' x; Start-Sleep 600"
    $args = if ($encoded) { "-NoProfile -EncodedCommand " + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code)) } else { "-NoProfile -Command `"$code`"" }
    $p = Start-Process powershell.exe -ArgumentList $args -WindowStyle Hidden -PassThru
    $deadline = (Get-Date).AddSeconds(30)
    while (-not (Test-Path "$file.ready") -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 100 }
    if (-not (Test-Path "$file.ready")) { throw "holder for $file never opened it" }
    return $p
}
function Remove-Only([string] $dir) {
    $ErrorActionPreference = "Continue"
    $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -RemoveOnly $dir 2>&1 | Out-String
    return @{ code = $LASTEXITCODE; out = $out }
}
function Checkout-Only([string] $version, [string] $dir) {
    $ErrorActionPreference = "Continue"
    $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -Version $version -CheckoutOnly $dir 2>&1 | Out-String
    return @{ code = $LASTEXITCODE; out = $out.Trim() }
}
# git with a fixed identity, its output dropped.
function G {
    $ErrorActionPreference = "Continue"
    & git -c user.name=fixture -c user.email=fixture@example.invalid -c init.defaultBranch=main @args 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "git $args failed" }
}

$holders = @()
try {
    # 1. named on its command line: stopped, and the home goes
    $h1 = New-Home "named home"
    $holders += Start-Holder (Join-Path $h1 "test-homes\slot\tmp\jk-git-1.err") $false
    $r = Remove-Only $h1
    Check ($r.code -eq 0) "a holder naming the home is stopped and the home goes (exit $($r.code)): $($r.out.Trim())"
    Check (-not (Test-Path -LiteralPath $h1)) "the named home is gone"
    Check ($r.out -match "stopping powershell.exe") "the stop is reported"

    # 2. past MAX_PATH: Remove-Item without the \\?\ form fails there with "Could not find a part of the path"
    $h3 = New-Home "deep home"
    # Join-Path refuses the \\?\ form, so the path is built as a string.
    $deep = "\\?\" + $h3 + "\store\templates\github.com_jumpkickoss_jk-templates"
    foreach ($i in 1..12) { $deep = $deep + '\$if(simple.truthy)$.$else$main$endif$' + $i }
    New-Item -ItemType Directory -Force -Path $deep | Out-Null
    [IO.File]::WriteAllText($deep + "\Note.java", "class Note {}")
    Check ($deep.Length -gt 300) "the fixture nests past MAX_PATH ($($deep.Length) characters)"
    $r = Remove-Only $h3
    Check ($r.code -eq 0) "a home nested past MAX_PATH goes (exit $($r.code)): $($r.out.Trim())"
    Check (-not (Test-Path -LiteralPath $h3)) "the deep home is gone"

    # 3. not named: the delete fails, names the holder, and keeps bin
    $h2 = New-Home "hidden home"
    $held = Join-Path $h2 "test-homes\slot\tmp\jk-git-2.err"
    $hidden = Start-Holder $held $true
    $holders += $hidden
    $r = Remove-Only $h2
    Check ($r.code -ne 0) "a holder the sweep cannot see fails the delete (exit $($r.code))"
    Check ($r.out -match [regex]::Escape("(pid $($hidden.Id)): $held")) "the failure names the holding process and the file: $($r.out.Trim())"
    Check (Test-Path -LiteralPath (Join-Path $h2 "bin\jk.cmd")) "bin is kept when the delete fails"
    Check (-not (Test-Path -LiteralPath (Join-Path $h2 "lib"))) "everything not held is deleted"
    Stop-Process -Id $hidden.Id -Force
    Wait-Process -Id $hidden.Id -Timeout 15 -ErrorAction SilentlyContinue
    $r = Remove-Only $h2
    Check ($r.code -eq 0) "once the holder exits the home goes (exit $($r.code))"
    Check (-not (Test-Path -LiteralPath $h2)) "the hidden home is gone"

    # 4. a stale local tag: origin holds A, B with v0.9.0 at A; the clone moves v0.9.0 to B; then
    #    origin gains C tagged v1.0.0, the release.
    $seed = Join-Path $root "seed"; $origin = Join-Path $root "origin.git"; $usual = Join-Path $root "usual"
    G init -q $seed
    G -C $seed commit -q --allow-empty -m A
    G -C $seed tag v0.9.0
    G -C $seed commit -q --allow-empty -m B
    G clone -q --bare $seed $origin
    G clone -q $origin $usual
    G -C $usual tag -f v0.9.0 HEAD
    G -C $seed commit -q --allow-empty -m C
    G -C $seed tag v1.0.0
    G -C $seed push -q $origin main v1.0.0
    $r = Checkout-Only "1.0.0" $usual
    Check (($r.code -eq 0) -and ($r.out -eq "usable")) "a checkout holding a stale local tag is usable (exit $($r.code)): $($r.out)"
    Check ((git -C $usual rev-parse HEAD) -eq (git -C $origin rev-parse v1.0.0)) "it is fast-forwarded to the release tag"
} finally {
    foreach ($p in $holders) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    foreach ($p in $holders) { Wait-Process -Id $p.Id -Timeout 15 -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath ("\\?\" + $root) -Recurse -Force -ErrorAction SilentlyContinue
}
if ($failures) { Write-Host "test-release-fleet-check: $failures failed"; exit 1 }
Write-Host "test-release-fleet-check: all passed"
