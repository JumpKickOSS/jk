# Publish and supply chain

```bash
jk publish
jk publish --sign
jk publish --sigstore
jk publish --slsa
jk publish --sbom
jk verify                 # rebuild in a scratch dir and diff artifact hashes
jk audit                  # OSV
jk deny                   # apply [deny.sources]
```

Real credentialed uploads are **CLI-only**. MCP `jk_publish` / `jk_run kind=publish` is
always a **dry-run** so tokens never enter the engine — [MCP](mcp.md).

Export a lock scope as a Maven BOM: `jk export bom` — [Platforms](platforms.md).

## Deny policy

```toml
[deny.sources]
deny = ["jcenter.bintray.com"]   # enforced at lock / jk deny (host match)
```

Host matching is exact or a DNS-label suffix (`evil.com` matches `repo.evil.com`, not
`notevil.com`).

License and yanked policies are **not** enforced yet. Those keys are **rejected at parse**
until enforcement ships — silent no-ops are not allowed.

## Audit

`jk audit` queries OSV for vulnerabilities in the locked graph. Lock rows pin a source
repo (namespace binding planned). Combine with exclusive repository groups —
[Repositories](repositories.md).

## Verify

`jk verify` rebuilds in a scratch directory and compares artifact hashes. Use it when you
need a rebuild-from-lock check, not as a substitute for the lockfile itself.

## Related

[Packaging](packaging.md) · [Repositories](repositories.md) · [Lockfile](lockfile.md)
