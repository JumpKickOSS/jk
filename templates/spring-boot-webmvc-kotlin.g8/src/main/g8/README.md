# $name$

Kotlin Spring Boot WebMVC service (JPA/Hibernate + H2, validation, Actuator). Scaffolded
from the jk `spring-boot-webmvc-kotlin` template — Spring Boot $spring_boot$, Kotlin $kotlin$.

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

No Kotlin compiler plugins required: classes Spring or JPA must subclass declare `open`
themselves, and the entity carries an explicit default constructor. If you prefer
`kotlin-spring`/`kotlin-jpa` semantics, wire them and drop the manual `open`s.

Upgrade path: bump `[spring-boot] version` (starters are version-managed) and the
`kotlin` pin in `jk.toml` together.
