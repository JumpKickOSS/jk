# ticket-1006 — Cross-package feature selection

**Priority:** P1 (library author UX)  
**Status:** open  
**Refs:** [guide.md](../guide.md) §7.8 (explicitly “planned; not yet implemented”),
[features](../guide.md) (local features vs profiles)

## Problem

Features today are **local** to the consuming project. A dependency cannot request
`features = ["mysql"]` of a library the way Cargo does. Library authors cannot express optional
capability sets for consumers.

## Direction (PRD sketch)

```toml
[dependencies]
widget = { group = "com.example", name = "widget", version = "0.3.1",
           features = ["mysql", "gson"], default-features = false }
```

- Publish feature metadata (jk.lock / POM extensions or `jk-features` sidecar — **decide in design**)
- Resolver activates optional deps of the **dependency’s** graph when features selected
- Lock records activated feature set for reproducibility

## Acceptance

- [ ] Design note linked from this ticket (short ADR in `docs/` or expand this file)
- [ ] One library + consumer fixture: consumer enables feature → optional dep appears in lock
- [ ] `default-features = false` honored

## Non-goals

- Feature flags that change source sets / variants (that is variants/profiles)
