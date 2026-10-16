# $name$

Grails 8 REST application (GORM + Hibernate 7, H2, Groovy) built with jk's
`[grails]` plugin. Scaffolded from the jk `groovy/grails/hello` template.

```bash
jk build
jk run       # REST API on :8080
curl -s localhost:8080/note
```

`[grails] version` pins the `grails-bom` release exactly; `"^8"` takes the newest 8.x
at each resolve instead. `groovy` pins the line that BOM manages. The `grails-bom` manages all `org.apache.grails`
dependency versions.
