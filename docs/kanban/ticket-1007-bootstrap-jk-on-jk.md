# ticket-1007 — Bootstrap / CI builds jk with jk

**Priority:** P1 (dogfood)  
**Status:** open  
**Refs:** [guide.md](../guide.md) §4 self-hosting, root `jk.toml`, `./gradlew dist`

## Problem

jk claims self-hosting but the shippable path is still `./gradlew dist`. Dual toolchains and
Gradle cross-daemon locks in this repo are a smell.

## Outcome

CI job (and documented contributor path) that produces `build/dist`-equivalent using `jk` only,
or a clear staged plan (Gradle only for native-image, jk for the rest) with an end state.

## Acceptance (MVP)

- [ ] Documented command sequence that builds engine jar + CLI without requiring Gradle for Java compile of modules already on jk
- [ ] CI green on that path for at least Linux

## Placeholder detail

Native-image / Graal builder may remain special-cased initially — call that out rather than blocking.
