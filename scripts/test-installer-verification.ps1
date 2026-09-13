# Network-free PowerShell 5.1 exercise of install.ps1: release authentication, the host-to-artifact
# mapping, download settings and the execution-policy default. Uses an ephemeral RSA-3072 key and
# never installs or executes the fixture artifact.
#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$installer = Join-Path $root "install.ps1"
$work = Join-Path ([IO.Path]::GetTempPath()) ("jk-installer-test-" + [guid]::NewGuid().ToString("N"))
$artifactName = "jk-windows-x86_64-1.0.0.zip"
$artifact = Join-Path $work $artifactName
$manifest = Join-Path $work "SHA256SUMS"
$signature = Join-Path $work "SHA256SUMS.sig"
$sentinel = Join-Path $work "prior-install.bin"
$rsa = New-Object Security.Cryptography.RSACryptoServiceProvider -ArgumentList 3072
$rsa.PersistKeyInCsp = $false
$public = $rsa.ExportParameters($false)
$modulus = [Convert]::ToBase64String($public.Modulus)
$exponent = [Convert]::ToBase64String($public.Exponent)
# The interpreter that re-runs the installer in verify-only mode. $PSHOME is the running engine's
# own directory on every host, so the executable beside it is this PowerShell; the process path is
# not when the host starts PowerShell through a runtime shim (a dotnet global tool, a container).
$powershell = if ($PSVersionTable.PSEdition -eq "Core") {
    Join-Path $PSHOME $(if ($IsWindows) { "pwsh.exe" } else { "pwsh" })
} else {
    Join-Path $PSHOME "powershell.exe"
}
if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) { throw "no PowerShell executable at $powershell" }

# The installer's functions are lifted from install.ps1 by their AST, so the script body never runs
# here; the cmdlets they call are shadowed below by recording functions.
$parseTokens = $null
$parseErrors = $null
$installerAst = [Management.Automation.Language.Parser]::ParseFile($installer, [ref] $parseTokens, [ref] $parseErrors)
if ($parseErrors.Count -gt 0) {
    throw "install.ps1 does not parse: $($parseErrors[0].Message) at line $($parseErrors[0].Extent.StartLineNumber)"
}
function Import-InstallerFunction([string] $Name) {
    $definition = $installerAst.Find({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name
    }.GetNewClosure(), $true)
    if (-not $definition) { throw "install.ps1 defines no function $Name" }
    $text = $definition.Extent.Text -replace "^function\s+$([regex]::Escape($Name))", "function script:$Name"
    . ([scriptblock]::Create($text))
}

$script:notes = @()
function script:Write-Note([string] $Message) { $script:notes += $Message }
function script:Write-Info([string] $Message) { $script:notes += $Message }
function script:Die([string] $Message) { throw $Message }

$script:seenProgress = @()
function script:Invoke-WebRequest {
    param([switch] $UseBasicParsing, [string] $Uri, [string] $OutFile)
    $script:seenProgress += [string] $ProgressPreference
    if ($OutFile) { [IO.File]::WriteAllText($OutFile, "fixture", [Text.Encoding]::ASCII) }
}

$script:policies = @()
$script:policyChanges = @()
function script:Get-ExecutionPolicy { param([switch] $List) return $script:policies }
function script:Set-ExecutionPolicy {
    param($Scope, $ExecutionPolicy, [switch] $Force)
    $script:policyChanges += "$Scope=$ExecutionPolicy"
}
function Set-PolicyFixture([string] $CurrentUser, [string] $LocalMachine, [string] $MachinePolicy = "Undefined") {
    $script:policies = @(
        [pscustomobject] @{ Scope = "MachinePolicy"; ExecutionPolicy = $MachinePolicy },
        [pscustomobject] @{ Scope = "UserPolicy"; ExecutionPolicy = "Undefined" },
        [pscustomobject] @{ Scope = "Process"; ExecutionPolicy = "Bypass" },
        [pscustomobject] @{ Scope = "CurrentUser"; ExecutionPolicy = $CurrentUser },
        [pscustomobject] @{ Scope = "LocalMachine"; ExecutionPolicy = $LocalMachine })
    $script:policyChanges = @()
    $script:notes = @()
}

function Get-ArtifactHash {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash([IO.File]::ReadAllBytes($artifact))
    } finally {
        $sha.Dispose()
    }
    return ([BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
}

function Write-Evidence([string] $Text) {
    $utf8 = New-Object -TypeName Text.UTF8Encoding -ArgumentList @($false)
    [IO.File]::WriteAllBytes($manifest, $utf8.GetBytes($Text))
    $sig = $rsa.SignData(
        [IO.File]::ReadAllBytes($manifest),
        [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    [IO.File]::WriteAllText($signature, [Convert]::ToBase64String($sig) + "`n", [Text.Encoding]::ASCII)
}

function Invoke-Verification {
    $output = & $powershell -NoProfile -ExecutionPolicy Bypass -File $installer `
        -VerifyOnlyDirectory $work `
        -VerifyOnlyArtifactName $artifactName `
        -TestRsaModulus $modulus `
        -TestRsaExponent $exponent 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        foreach ($line in $output) { Write-Host $line }
    }
    return $code
}

function Assert-Fails([string] $Case) {
    $code = Invoke-Verification
    if ($code -eq 0) { throw "$Case unexpectedly verified" }
    if ([Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($sentinel)) -cne "prior") {
        throw "$Case changed the prior installation sentinel"
    }
}

New-Item -ItemType Directory -Force -Path $work | Out-Null
try {
    [IO.File]::WriteAllBytes($artifact, [Text.Encoding]::ASCII.GetBytes("fixture archive"))
    [IO.File]::WriteAllBytes($sentinel, [Text.Encoding]::ASCII.GetBytes("prior"))

    Write-Evidence "$(Get-ArtifactHash)  $artifactName`n"
    if ((Invoke-Verification) -ne 0) { throw "valid evidence did not verify" }

    [IO.File]::WriteAllBytes($artifact, [Text.Encoding]::ASCII.GetBytes("tampered archive"))
    Assert-Fails "tampered artifact"
    [IO.File]::WriteAllBytes($artifact, [Text.Encoding]::ASCII.GetBytes("fixture archive"))

    Write-Evidence "$(Get-ArtifactHash)  $artifactName`n"
    [IO.File]::WriteAllText($manifest, ("0" * 64) + "  $artifactName`n", [Text.Encoding]::ASCII)
    Assert-Fails "tampered metadata"

    Write-Evidence "$(Get-ArtifactHash)  wrong.zip`n"
    Assert-Fails "wrong artifact name"

    # Rollback: another release's valid manifest under this version's directory names only that
    # release's artifacts.
    Write-Evidence "$(Get-ArtifactHash)  jk-windows-x86_64-0.9.0.zip`n"
    Assert-Fails "other release manifest"

    # Strict LF, as install.sh and the Java verifier: a CRLF manifest is a different file.
    Write-Evidence "$(Get-ArtifactHash)  $artifactName`r`n"
    Assert-Fails "crlf manifest"

    $hash = Get-ArtifactHash
    Write-Evidence "$hash  $artifactName`n$hash  $artifactName`n"
    Assert-Fails "duplicate artifact name"

    Write-Evidence "$(Get-ArtifactHash)  $artifactName`n"
    $other = New-Object Security.Cryptography.RSACryptoServiceProvider -ArgumentList 3072
    try {
        $other.PersistKeyInCsp = $false
        $wrongSignature = $other.SignData(
            [IO.File]::ReadAllBytes($manifest),
            [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        [IO.File]::WriteAllText(
            $signature, [Convert]::ToBase64String($wrongSignature) + "`n", [Text.Encoding]::ASCII)
    } finally {
        $other.Dispose()
    }
    Assert-Fails "untrusted key"

    Write-Evidence "$(Get-ArtifactHash)  $artifactName`n"
    Remove-Item -LiteralPath $signature -Force
    Assert-Fails "missing signature"

    # ---- the signed latest-release pointer -------------------------------------------------
    Import-InstallerFunction "Test-ReleaseSignature"
    Import-InstallerFunction "Get-ReleasePointerVersion"
    $pointer = Join-Path $work "LATEST"
    $pointerSignature = Join-Path $work "LATEST.sig"
    function Write-Pointer([string] $Text, [Security.Cryptography.RSA] $Signer = $rsa) {
        [IO.File]::WriteAllBytes($pointer, [Text.Encoding]::ASCII.GetBytes($Text))
        $sig = $Signer.SignData(
            [IO.File]::ReadAllBytes($pointer),
            [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        [IO.File]::WriteAllText($pointerSignature, [Convert]::ToBase64String($sig) + "`n", [Text.Encoding]::ASCII)
    }
    function Resolve-Pointer([string] $Floor = "1.0.0") {
        return Get-ReleasePointerVersion -Pointer $pointer -Signature $pointerSignature `
            -Modulus $modulus -Exponent $exponent -Floor $Floor
    }
    function Assert-PointerRefused([string] $Case, [string] $Expected) {
        try {
            $got = Resolve-Pointer
            throw "$Case unexpectedly resolved to $got"
        } catch {
            if ($_.Exception.Message -notmatch [regex]::Escape($Expected)) { throw "$Case was refused for another reason: $($_.Exception.Message)" }
        }
    }

    Write-Pointer "version 1.0.0`nissued 1757700000`n"
    if ((Resolve-Pointer) -cne "1.0.0") { throw "a valid pointer did not resolve to its version" }
    Write-Pointer "version 1.2.0-rc.1`nissued 1757700000`n"
    if ((Resolve-Pointer) -cne "1.2.0-rc.1") { throw "a pre-release pointer did not resolve to its version" }

    Remove-Item -LiteralPath $pointerSignature -Force
    Assert-PointerRefused "unsigned pointer" "Could not find file"

    Write-Pointer "version 1.0.0`nissued 1757700000`n"
    [IO.File]::WriteAllBytes($pointer, [Text.Encoding]::ASCII.GetBytes("version 1.0.1`nissued 1757700000`n"))
    Assert-PointerRefused "tampered pointer" "latest-release pointer signature verification failed"

    # Rollback: an older release's pointer, validly signed, re-served as the latest one.
    Write-Pointer "version 0.9.0`nissued 1757700000`n"
    Assert-PointerRefused "rolled-back pointer" "names 0.9.0, older than the 1.0.0 this installer ships with"

    $foreign = New-Object Security.Cryptography.RSACryptoServiceProvider -ArgumentList 3072
    try {
        $foreign.PersistKeyInCsp = $false
        Write-Pointer "version 1.0.0`nissued 1757700000`n" $foreign
    } finally {
        $foreign.Dispose()
    }
    Assert-PointerRefused "pointer signed by an untrusted key" "latest-release pointer signature verification failed"

    # Signed, but not the two-line form the signer writes.
    foreach ($malformed in @(
            "version 1.0.0`r`nissued 1757700000`r`n",
            "1.0.0`n",
            "version 1.0.0`n",
            "version 1.0.0`nissued 1757700000",
            "version 1.0.0`nissued 1757700000`nversion 0.9.0`n",
            "version ../1.0.0`nissued 1757700000`n",
            "version 1.0.0`nissued soon`n")) {
        Write-Pointer $malformed
        Assert-PointerRefused "malformed pointer '$($malformed -replace "`r", '\r' -replace "`n", '\n')'" "latest-release pointer is malformed"
    }

    # ---- host mapping: releases publish windows-x86_64 only ---------------------------------
    Import-InstallerFunction "Get-JkTarget"
    $script:notes = @()
    $target = Get-JkTarget -ArchName "Arm64" -ProcessorArchitecture "ARM64"
    if ($target -cne "windows-x86_64") { throw "Arm64 mapped to $target instead of the windows-x86_64 build" }
    if (-not ($script:notes -match "windows-x86_64")) { throw "Arm64 mapping printed no note naming the x86_64 build" }
    $script:notes = @()
    if ((Get-JkTarget -ArchName "X64" -ProcessorArchitecture "AMD64") -cne "windows-x86_64") { throw "X64 did not map to windows-x86_64" }
    if ($script:notes.Count -ne 0) { throw "X64 mapping printed a note: $($script:notes -join ' | ')" }
    if ((Get-JkTarget -ArchName "" -ProcessorArchitecture "ARM64") -cne "windows-x86_64") { throw "PROCESSOR_ARCHITECTURE=ARM64 fallback did not map to windows-x86_64" }
    if ((Get-JkTarget -ArchName "" -ProcessorArchitecture "x86") -cne "windows-x86_64") { throw "PROCESSOR_ARCHITECTURE=x86 fallback did not map to windows-x86_64" }
    try {
        Get-JkTarget -ArchName "Mips" -ProcessorArchitecture "MIPS" | Out-Null
        throw "an unknown architecture was accepted"
    } catch {
        if ($_.Exception.Message -notmatch "unsupported architecture") { throw }
    }

    # ---- downloads run with the progress bar off, without touching the caller's preference ---
    Import-InstallerFunction "Save-Url"
    $script:seenProgress = @()
    $ProgressPreference = "Continue"
    Save-Url "https://fixture/releases/1.0.0/x.zip" (Join-Path $work "download.zip")
    Save-Url "https://fixture/releases/latest/LATEST" (Join-Path $work "download-pointer")
    if (($script:seenProgress -join ",") -cne "SilentlyContinue,SilentlyContinue") {
        throw "downloads ran with ProgressPreference $($script:seenProgress -join ',')"
    }
    if ([string] $ProgressPreference -cne "Continue") { throw "a download changed the caller's ProgressPreference" }

    # ---- execution policy: printed by default, applied only on request -----------------------
    Import-InstallerFunction "Ensure-ProfileExecutionPolicy"
    $suggestion = "Set-ExecutionPolicy -Scope CurrentUser RemoteSigned"

    Set-PolicyFixture -CurrentUser "Undefined" -LocalMachine "Restricted"
    Ensure-ProfileExecutionPolicy
    if ($script:policyChanges.Count -ne 0) { throw "the default changed the execution policy: $($script:policyChanges -join ',')" }
    if (-not ($script:notes -match [regex]::Escape($suggestion))) { throw "a blocking policy did not print the $suggestion suggestion" }

    Set-PolicyFixture -CurrentUser "Undefined" -LocalMachine "Restricted"
    Ensure-ProfileExecutionPolicy -Apply $true
    if (($script:policyChanges -join ",") -cne "CurrentUser=RemoteSigned") { throw "opting in did not set CurrentUser RemoteSigned: $($script:policyChanges -join ',')" }

    Set-PolicyFixture -CurrentUser "RemoteSigned" -LocalMachine "Restricted"
    Ensure-ProfileExecutionPolicy -Apply $true
    if ($script:policyChanges.Count -ne 0 -or $script:notes.Count -ne 0) { throw "a permissive policy was changed or reported" }

    Set-PolicyFixture -CurrentUser "Undefined" -LocalMachine "Restricted" -MachinePolicy "AllSigned"
    Ensure-ProfileExecutionPolicy -Apply $true
    if ($script:policyChanges.Count -ne 0) { throw "a policy locked by MachinePolicy was changed" }
    if (-not ($script:notes -match "MachinePolicy")) { throw "a locked policy did not name the locking scope" }

    Write-Host "PowerShell installer verification fixtures passed."
} finally {
    $rsa.Dispose()
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
exit 0
