# ticket-1012 — Remote cache design (read-only first)

**Priority:** P2 (post-GA shape, design now)  
**Status:** open / placeholder  
**Refs:** [guide.md](../guide.md) §17.4, §30; action keys in engine `task/`

## Intent

Design local action-key + CAS layout so a later Bazel REAPI (or HTTP CAS) read-only client does
not force key rewrites. Implementation can wait for GA.

## Placeholder

- ADR: key ingredients (task type, input hashes, toolchain, jk version, os/arch when needed)
- Explicit non-goals for v1.0 shipping
