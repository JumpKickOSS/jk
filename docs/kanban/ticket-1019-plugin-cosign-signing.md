# ticket-1019 — Plugin content signing beyond sha256 (cosign)

**Priority:** P3  
**Status:** backlog (refined)  
**Kind:** go-do  
**Source:** enterprise trust / supply-chain depth  
**Depends on:** ticket-1010 private plugins (**done** — sha256 pin required)  
**Branch:** `ticket-1019-plugin-cosign`  
**Estimate:** M–L (2–3 days)  
**Refs:** private plugin pin path, lock/plugin load verification, cosign/Sigstore CLI or library

## Problem

Private plugins require a **sha256 content pin** (fail-closed). Enterprises also want **identity-
bound signatures** (cosign/Sigstore) so the pin is not only “hash someone wrote in TOML” but
“hash signed by our identity.”

## Design constraints

1. **sha256 remains required** — signatures are additive, never a replacement.  
2. Verification at **lock** and/or **load** time; fail closed on mismatch.  
3. No network in offline mode unless user opts into key/rekor fetch (document).  
4. Data-only `jk.toml`: e.g. `signature = "cosign.bundle"` or `cosign-identity = "…"` next to pin.

## Proposed config sketch (not final)

```toml
[plugins.my-tool]
coordinate = "com.example:my-tool:1.2.3"
sha256 = "…"
# optional additive:
cosign-bundle = "plugins/my-tool.sigstore.json"
# or identity mode when using keyless:
# cosign-identity = "https://github.com/acme/my-tool/.github/workflows/release.yml@refs/tags/v1.2.3"
```

## Implementation outline

1. Spike: verify one jar with cosign bundle offline (fixture).  
2. Wire into plugin materialize/lock path used today for sha256.  
3. Errors: distinguish hash fail vs signature fail vs missing cosign tool.  
4. Docs in plugins / private plugins section.

## Acceptance

- [ ] Path (or Maven) pin + valid cosign bundle → load succeeds  
- [ ] Tampered jar → fail closed with clear error  
- [ ] Missing bundle when signature **required by policy** → fail (if we add require flag); if optional, document skip  
- [ ] Docs: optional signing flow; offline caveats  
- [ ] sha256 still mandatory in all cases  

## Non-goals

- Plugin marketplace registry  
- Replacing sha256  
- GPG-only path (cosign first; GPG later if needed)  
- Signing first-party JumpKick releases (separate release engineering)  

## Ready criteria

- Customer/enterprise request or security roadmap pull; not ahead of packaging/IDE UX  
