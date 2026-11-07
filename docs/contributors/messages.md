# Writing user-facing messages

Errors, warnings, notes, progress text, results files, import reports and the web UI are read by
developers, not by agents. Write them the way a good CLI talks: short, plain, and actionable.

## The rules

1. **One line, 78 columns or less**, counting the prefix the renderer adds (`  ! `, ` 1) `). Fixed
   text should be short even when an interpolated path or coordinate makes the line long. If a
   second line is truly needed, make it a short hint, not a continuation of the sentence.
2. **Subject first:** `<what>: <what's wrong>`, then `; <what to do>` when there is a fix.
   `repositories.gitlab.credential-only: only in ~/.jk/config.toml`
3. **Plain words.** Say "dependency", "module", "version", "repository". Not jk's internal terms:
   floor, edge, leg, lane, unit, frontier, partition, closure, probe, cascade, wedge.
4. **What happened and what to do, not why the design is so.** The reasoning goes in `docs/user/`.
5. **No essay grammar:** no stacked "which/whose/so that" clauses, no em-dash or semicolon chains,
   no parenthetical asides, no "note that", no hedging, no restating the obvious.
6. **Keep what the user acts on:** the exact file, key, coordinate, flag or command. Shorter is not
   better if it drops the one thing they need.
7. **Style:** lowercase first word for errors and notes, no trailing period on a one-clause message,
   `jk <verb>` in backticks only in markdown output.

## What this does not cover

- MCP payloads and `-O jsonl` fields read by agents may say more (they still should not waste
  tokens). Machine codes, enum values and JSON keys never change for style.
- Debug lines that only reach the engine log.
- Exception messages for internal invariants no user sees (`requireNonNull` messages).
- Prose in `docs/user/`, which explains at whatever length it needs.

## Examples

| Before | After |
|---|---|
| `workspace build graph has a cycle: a → b → a (a [dependencies] names b; b [dependencies] names a). Only a test-scope edge may close a loop between members.` | `dependency cycle: a → b → a` |
| `an exact pin read as a floor rises only to a version a dependency asks for, and none of 7.4.8, 7.5.0 fits` | `pinned 7.4.8: no version the graph asks for fits` |
| `gitlab: ${env:TOKEN} moved to ~/.jk/config.toml. On GitLab CI, export JK_REPO_GITLAB_USERNAME=gitlab-ci-token, …` | `gitlab: credential added to ~/.jk/config.toml` |
| `Pins not checked against their dependencies (…); jk lock names any pin set too low` | `pins not checked; jk lock reports any set too low` |
