# $name$

Grails 8 REST application (GORM + Hibernate 7, H2, Groovy) built with jk's
`[grails]` plugin. Scaffolded from the jk `grails-8` template.

```bash
jk build
jk run       # REST API on :8080
curl -s localhost:8080/note
```

Grails 8 is still a milestone, so `[grails] version` pins `8.0.0-M4` (a stable
selector would pick the last Grails 7 release). `jk update` moves the pin; `groovy`
pins the line that BOM manages. The `grails-bom` manages all `org.apache.grails`
dependency versions.
