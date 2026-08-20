# $name$

Grails 8 REST application (GORM + Hibernate 7, H2, Groovy) built with jk's
`[grails]` plugin. Scaffolded from the jk `grails-8` template.

```bash
jk build
jk run       # REST API on :8080
curl -s localhost:8080/note
```

Grails 8 is still a milestone, so `[grails] version` floors at `8.0.0-M4` (caret)
instead of `"latest"` (that would pick the last stable Grails 7). `jk lock` pins the
current 8.x; switch the floor to `"latest"` after 8.0.0 GA. Groovy is `"latest"`.
The `grails-bom` manages all `org.apache.grails` dependency versions.
