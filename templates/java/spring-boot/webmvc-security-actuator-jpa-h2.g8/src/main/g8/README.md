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

Upgrade path: `jk update` re-locks `[spring-boot] version = "latest"` to a newer
stable; starters stay versionless under the plugin BOM. Use `=4.1.0` to freeze.
