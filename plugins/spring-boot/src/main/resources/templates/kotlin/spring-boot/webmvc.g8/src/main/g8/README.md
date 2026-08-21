# $name$

Multi-module Spring Boot WebMVC app (Kotlin, Java $java$ bytecode, virtual threads, JOOQ).
Scaffolded from the jk `spring-boot` plugin kind `webmvc`.

```text
data/     JOOQ repositories, Flyway migrations, H2
server/   services, DTOs, controllers, Security, Tomcat
client/   static Vue 3 UI (CDN), served from the server classpath
```

Default login: `user` / `password` (HTTP Basic). First `jk lock` pins the current Spring Boot
line from `[spring-boot] version = "latest"`.

```bash
jk lock
jk test
jk run -m server          # http://localhost:8080
```

```bash
curl -s -u user:password localhost:8080/api/notes
curl -s -u user:password -X POST localhost:8080/api/notes \
  -H 'content-type: application/json' -d '{"body":"hello"}'
```
