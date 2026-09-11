# $name$

Ktor 3 service with Koin dependency injection and Exposed (H2 in-memory) persistence.
Scaffolded from the jk `ktor-3` template.

```bash
jk build
jk test      # ktor-server-test-host in-process tests
jk run       # serve on :8080
```

Try it:

```bash
curl -s localhost:8080/api/notes
curl -s -X POST localhost:8080/api/notes -H 'content-type: application/json' -d '{"text":"first"}'
```

Versions in `jk.toml` are `"latest"`; first `jk lock` pins the current stable set
(`jk update` refreshes). JSON uses Jackson content negotiation, so no
kotlinx-serialization compiler plugin is involved.

## Tiers, guards, image

`jk test` is the fast tier; `jk test --profile integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost. No framework pack fits a plain service, so `jk-guards.toml` is a small
house baseline: a `file-size` cap, and a `forbid` that proves it bites against
`guard-fixtures/no-soft-reference/` (`jk guard explain` lists both). `jk image` builds an OCI image
on a JRE base with an AOT cache trained at build time (`[image]` in `jk.toml`).
