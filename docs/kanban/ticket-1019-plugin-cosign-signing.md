# ticket-1019 — Plugin content signing beyond sha256 (cosign)

**Priority:** P3  
**Status:** backlog  
**Depends on:** ticket-1010 private plugins (**done**)  
**Branch:** `ticket-1019-plugin-cosign`

## Learned from 1010

Private plugins now require a **sha256 content pin** (path or Maven). That is the trust root for
fail-closed loads. Enterprises often also want **signatures** (Sigstore/cosign, GPG) so pins can
be verified against an identity, not only a hash written in TOML.

## Scope (when refined)

- Optional `signature` / cosign bundle next to the plugin jar
- Verify at lock time; clear errors on mismatch
- Keep sha256 required; signatures are additive

## Acceptance (draft)

- [ ] Path pin with valid cosign verification succeeds
- [ ] Tampered jar fails closed
- [ ] plugins.md documents the optional signing flow

## Out of scope

- Marketplace registry
- Replacing sha256 pins
