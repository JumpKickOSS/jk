# ticket-1002 — Solver package identity includes type/classifier

**Priority:** P0 (resolver fidelity)  
**Status:** ready  
**Branch:** `ticket-1002-classifier-identity`  
**Refs:** [architecture.md](../architecture.md) (resolution § — package identity note),
`server/resolver` PubGrub stack (`Term`, `MavenPackageSource`, `PubGrubSolver`),
`shared/jk-api/.../Coordinate.java`, lock read/write under `shared/core/.../lock/`

## Problem

PubGrub package keys are **`group:artifact` only**. Classifiers (`linux-x86_64`, `tests`, …)
and non-`jar` types collapse into one version decision. Wrong artifact can win; two
classifiers of the same GA cannot coexist as distinct packages.

Maven already treats identity as `group:artifact:type:classifier` in POM/layout paths;
the solver must match.

## Direction

1. **Package key** → `group:artifact:type:classifier`  
   - Defaults: type `jar`, classifier empty (canonical string form TBD — prefer explicit
     empty classifier over ambiguous trailing colons; document in ticket close-out).
2. Thread through: `Term.pkg`, lockfile package rows (`name` and/or structured fields),
   BOM / managed maps, `jk tree` / `jk why`, dependsOn strings in diagnostics.
3. **BOM management** stays GA-scoped: a managed version still applies to all classifiers
   of that GA unless management is classifier-specific (rare; document behavior).

## Suggested slice order

1. Introduce key helper + unit tests (`:resolver`) with mock `PackageSource`
2. `MavenPackageSource` expand / dependsOn emit full identity
3. Lock read/write: write full keys; **read** bare `g:a` as `g:a:jar:` (migration)
4. Classpath / sync selection uses full keys so dual classifiers both land
5. CLI tree/why display remains human-readable (omit default type/classifier in UI if noisy)

## Acceptance

- [ ] Two classifiers of the same GA can lock at the same version as **distinct** rows/keys
- [ ] Netty-style native classifier + plain jar both resolve without clobbering each other
- [ ] `:resolver` unit tests with mock repo; at least one dual-classifier fixture
- [ ] Old locks with bare `g:a` still load (default type/classifier)
- [ ] One sentence in [architecture.md](../architecture.md) resolution section updated
      (package identity is no longer “GA only”)

## Out of scope

- Changing Maven Central coordinates or inventing new classifiers
- Wire protocol freeze (ticket-1001) — only needed if lock/report JSON grows fields
- Conflict suggestion polish (ticket-1005) — better coords help later; not required here

## Depends on

None. Can parallel with 1001 on a separate worktree. Prefer landing **before** 1005 if
suggestions should print full coordinates.
