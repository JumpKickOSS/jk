# Wrapper

```bash
jk wrapper                     # emit ./jk + jk.bat bootstrap scripts
jk wrapper update              # refresh the committed scripts
```

The wrapper is a **bootstrapper, not a pin**: it runs the newest installed `jk` that
satisfies the lock’s optional `jk-min` floor, otherwise fetches the latest release
(verified against the signed `SHA256SUMS`, then its exact artifact digest). Unix wrappers
require OpenSSL; Windows wrappers use the RSA implementation built into PowerShell 5.1.

Team version alignment belongs to the installer / CI images, never to a lock row.
`jk update` re-resolves **dependencies**; `jk self update` updates **the tool** — two
deliberate steps.

## Related

[Install](install.md) · [Lockfile](lockfile.md)
