# $name$

Spring Boot WebMVC service (JPA/Hibernate + H2, validation, Actuator). Scaffolded from
the jk `spring-boot-webmvc` template. First `jk lock` pins the current Spring Boot
line from `[spring-boot] version = "latest"`.

```bash
jk build     # boot-aware packaging via the built-in [spring-boot] plugin
jk test      # @SpringBootTest against in-memory H2
jk run       # serve on :8080
```

Try it:

```bash
curl -s localhost:8080/api/notes
curl -s -X POST localhost:8080/api/notes -H 'content-type: application/json' -d '{"text":"first"}'
curl -s localhost:8080/actuator/health
```

Upgrade path: `[spring-boot] version` is an exact pin; `jk update` moves it to the newest stable
on the same major (`--major` crosses one). Starters stay versionless under the plugin BOM.

## Tiers, guards, image

`jk test` is the fast tier; `jk test -p integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost. `jk-guards.toml` holds the house rules — `jk guard explain` lists them,
and a failure's `Instead:` line says what to do. `jk image` builds an OCI image on a JRE base with an
AOT cache trained at build time (`[image]` in `jk.toml`).
