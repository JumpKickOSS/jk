# Comments and Javadoc

Public-facing style for this repository: source, tests, and contributor docs.
Authoritative agent protocol: [../../AGENTS.md](../../AGENTS.md#comments-and-javadoc).

## Defaults

- **Short.** One or two sentences for roughly 80%+ of Javadoc and comments.
- **Longer only when earned.** Complex types/methods, or invariants / units /
  wire-or-disk format / null rules the signature does not carry.
- **Facts, not history.** Document what is true *now*.

## Allowed references

- In-repo docs under `docs/…`
- Stable public URLs (JDK, Maven, language specs)
- `{@link}` / `{@see}` to code

## Forbidden

- Ticket / issue ids (`JK-1234`, forge `#1234`, KanArtist URLs) in Javadoc,
  `package-info`, ordinary comments, **tests**, or these contributor pages
- Narration: formerly / used to / landed in / back-compat / “for agents”
- Decision essays and PR play-by-play in source
- Comments that only restate the next line of code

## Temporary follow-up only

Ticket ids may appear **only** as `// TODO:` / `// FIXME:` for unfinished scoped
work. Delete them when the work lands. Never put them in Javadoc “for context.”

```java
// TODO(JK-NNNN): rank importers when the dirty module is selected via -m
```

## User-facing product output

Never put `JK-…` (or any internal ticket id) in errors, warnings, user-visible
logs, CLI help, progress text, results markdown, or HTTP/MCP payloads. That is a
bug: strip the id; keep the diagnosis.

How to word that output: [User-facing messages](messages.md).

## Docs say what is

Until 1.0.0 the docs, the skill pages, the template READMEs, the website and the unreleased
What's new entry describe the present only: no "was named" or "renamed from" notes, no "before
0.60.0" asides, no `**Breaking:**` bullets, no "used to" narration. Write the command, key or
behaviour as it is now. Entries of released versions in [What's new](../user/whats-new.md) stay as
written. `jk guard docs` flags the common shapes (`docs-say-what-is`); `scripts/release-notes.sh`
refuses them in the entry being written.

## History belongs elsewhere

KanArtist tickets / `projects/jk/docs/`, or the commit body — not novels in the
tree, and not decision essays in [../user/](../user/README.md).

## Good vs bad

**Good**

```java
/**
 * Warn when a module declares {@code [test-dependencies]} but has no test source files.
 * Raised via {@link TaskContext#warn} so it is attributed to this module/step in results.
 */
```

**Bad**

```java
/**
 * … the shape of JK-NNNN, where a stale scan made a module's real suite
 * invisible … The propagation fix closes that hole …
 */
```
