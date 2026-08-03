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

Versions in `jk.toml` are caret ranges (latest compatible resolves at lock time);
`jk.lock` pins the exact set. JSON uses Jackson content negotiation, so no
kotlinx-serialization compiler plugin is involved.
