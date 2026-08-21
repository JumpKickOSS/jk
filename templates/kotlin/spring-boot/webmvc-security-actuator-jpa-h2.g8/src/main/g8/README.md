# $name$

Kotlin Spring Boot WebMVC service (JPA/Hibernate + H2, validation, Actuator). Scaffolded
from the jk `spring-boot-webmvc-kotlin` template. First `jk lock` pins the current
Spring Boot and Kotlin lines from `"latest"`.

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

Upgrade path: `jk update` re-locks `"latest"` selectors (Boot BOM and Kotlin) to a
newer stable. Use `=4.1.0` / `=2.4.10` to freeze.
