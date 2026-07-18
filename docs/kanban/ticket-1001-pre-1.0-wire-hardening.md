# ticket-1001 — Pre-1.0 wire protocol + CLI freeze

**Priority:** P0 (1.0 contract)  
**Status:** open  
**Refs:** [architecture.md](../architecture.md) (engine + wire overview); historical hardening notes in git history

## Problem

Before 1.0, the client↔engine JSONL protocol and several CLI surfaces still have silent
overwrite bugs, inconsistent field names, and non-self-describing terminal messages. Freezing
that mess makes every later change a compat tax.

## Scope (historical hardening plan in git — execute, don’t re-litigate)

### P0 correctness (must land)

- Fix `--version` collision (`GlobalOptions` vs `self update` / `wrapper` value options)
- Engine→engine TCP auth must use the same `{"t":"auth",…}` envelope as the server
- Delegation child stderr drain (hang on version-skew path)
- `install.sh` must materialize versions via CAS (one materializer)
- Repeatable flags actually `.repeat()` (`--variant`, `--with`)
- Atomic materialize for repo artifacts (`.part` + `ATOMIC_MOVE`)

### P1 protocol freeze candidate

- Discriminator on `pipeline-finish` (`kind`)
- One transport error envelope (`{"t":"error","code",…}`)
- One spelling for project directory (`dir`)
- Fold variant/JVM tuning into real request builders (no string surgery)
- One map encoding; one null convention
- Honor `proto` both directions; honest probe `purpose`
- Bounded line reader; no silent drop of garbled requests; streaming idle timeout

## Acceptance

- [ ] Hardening plan P0 items closed with tests named in that doc
- [ ] Protocol messages used by hosted verbs carry `kind` on finish / unified errors
- [ ] `./gradlew test` + dist smoke; no dual field spellings for `dir` / `startedAtMillis`
- [ ] Short note in `docs/protocol.md` or engine doc: “1.0 freeze vocabulary”

## Non-goals

- Stable public protocol for third-party engines (still same-version client/server)
- IDE feature work (see ticket-1014)
