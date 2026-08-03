# $name$

Grails $grails$ REST application (GORM + Hibernate 7, H2, Groovy $groovy$) built with jk's
`[grails]` plugin. Scaffolded from the jk `grails-8` template.

```bash
jk build
jk run       # REST API on :8080
curl -s localhost:8080/note
```

Grails 8 is the Apache Grails line (Spring Boot 4 based); this template tracks the latest
milestone until GA — bump `[grails] version` in `jk.toml` when GA lands. The
`grails-bom` manages all `org.apache.grails` dependency versions.
