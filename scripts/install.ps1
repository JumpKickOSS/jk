# Forwards to the repo-root installer (public entrypoint: install.ps1 / irm|iex).
# Kept so older docs and muscle memory (`pwsh -File scripts/install.ps1 ...`) keep working.
$root = Split-Path -Parent $PSScriptRoot
$installer = Join-Path $root "install.ps1"
& $installer @args
$code = $LASTEXITCODE
if ($null -eq $code) { $code = 0 }
exit $code
