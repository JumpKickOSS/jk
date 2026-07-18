# ticket-1002 — Solver package identity includes type/classifier

**Priority:** P0/P1 (resolver fidelity)  
**Status:** open  
**Refs:** [architecture.md](../architecture.md) §P1.2, [guide.md](../guide.md),
`MavenPackageSource`, `Coordinate`, `EffectivePomBuilder` (Maven identity already
`group:artifact:type:classifier` in places)

## Problem

PubGrub package keys are `group:artifact` only. Classifiers (`linux-x86_64`, `tests`,
sources-adjacent artifacts) and non-jar types collapse into one version decision. Wrong jar
can win; two classifiers of the same GA cannot coexist.

## Direction

1. **Package key** → `group:artifact:type:classifier` (type default `jar`, classifier default empty)
2. Thread through: `Term.pkg`, lockfile `name` (or structured fields), BOM maps, `jk tree`/`why`,
   dependsOn strings
3. Preserve BOM GA management: managed version still applies to all classifiers of that GA unless
   management is classifier-specific (rare)

## Acceptance

- [ ] Two classifiers of same GA can lock at the same version as distinct rows/keys
- [ ] Netty-style native classifier deps resolve without clobbering the plain jar
- [ ] Unit tests in `:resolver` with mock repo; at least one dual-classifier fixture
- [ ] Migration note: old locks with bare `g:a` still read (default type/classifier)

## Risks

Large blast radius (lock schema, catalog, sync, IDE classpath). Prefer a single stacked PR series
after ticket-1001 protocol freeze if both touch wire-visible names.
