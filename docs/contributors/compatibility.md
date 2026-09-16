# Compatibility policy

What a release may change in the formats a project depends on, and how a breaking change is
announced. Every repo that adopts jk commits a `jk.toml` and a `jk-lock.toml` and points agents
at `target/jk-results.md` and the MCP tools; those are the surfaces this page governs. The
pre-1.0 freeze itself is stated in [Architecture](architecture.md#schema-freeze-until-10).

## Before 1.0

Every schema and protocol number stays at **1**. `jk.toml` grows additively. A lock written by
0.x is read by every later 0.y and rewritten in the newer shape on the next `jk lock`. Anything
else can change between minors; the release entry in [Releases](releases.md) says what did.

## From 1.0

| Surface | Within a major (`1.x` → `1.y`) | Only in a major (`1.x` → `2.0`) |
|---|---|---|
| `jk.toml` keys and tables | Add keys; deprecate a key with a warning for two minor releases | Remove a key or change its meaning |
| `jk-lock.toml` | Add rows or fields; every `1.y` reads every lock a `1.x` wrote; an older `1.x` reads a newer lock by ignoring unknown fields with one warning | Bump `version`; remove a field |
| `jk-results.md` sections and `details.jsonl` event kinds | Add sections, fields and kinds | Remove or rename a section, field or kind |
| MCP tools | Add tools and optional arguments; deprecate a tool with a notice in its description for two minors | Remove a tool or a required argument |
| Plugin SDK (`cc.jumpkick:jk-plugin-sdk`) | Additive SPI; a plugin built against `1.x` loads on every `1.y` at or above its `jk-compat` floor | Remove or change an SPI method |
| CLI verbs and flags | Rename by adding the new spelling and keeping the old as an alias with a deprecation line | Drop the alias |
| Client ↔ engine wire | Private; the pair ships together and is version-matched | Same |

A deprecation is a one-line warning naming the replacement, printed once per invocation, and
listed by `jk doctor`. `jk import` and `jk fix` rewrite the deprecated spelling when the change is
mechanical.

## Announcing

A breaking change has a `### <version>` entry in [Releases](releases.md) whose first bullet starts
with **Breaking:** and names the migration. The first `jk` invocation after an upgrade prints the
release's Breaking bullets once. A major release ships a `jk migrate` that applies every
mechanical rewrite listed there.

## Related

- [The 1.0 plan](plan-1.0.md) · [Architecture](architecture.md) · [Releases](releases.md)
