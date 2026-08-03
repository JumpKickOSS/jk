# $name$

Spring Boot WebMVC service (JPA/Hibernate + H2, validation, Actuator). Scaffolded from
the jk `spring-boot-webmvc` template — Spring Boot $spring_boot$.

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

Upgrade path: bump `[spring-boot] version` in `jk.toml`; starters are version-managed by
the plugin, so no per-dependency pins to touch.
