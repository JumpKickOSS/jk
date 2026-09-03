# Network-free PowerShell 5.1 exercise of install.ps1 release authentication.
# Uses an ephemeral RSA-3072 key and never installs or executes the fixture artifact.
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
$powershell = (Get-Process -Id $PID).Path

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

    Write-Host "PowerShell installer verification fixtures passed."
} finally {
    $rsa.Dispose()
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
