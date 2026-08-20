# $name$

Micronaut HTTP service (compile-time DI, Netty server). Scaffolded from the jk
`micronaut` template. First `jk lock` pins the current Micronaut platform from
`[micronaut] version = "latest"`.

```bash
jk build
jk test      # @MicronautTest with embedded server + HTTP client
jk run       # serve on :8080
curl -s 'localhost:8080/hello?name=jk'
```

The `[micronaut]` plugin imports `micronaut-platform`; `[processor-dependencies]`
wires `micronaut-inject-java` so bean definitions are generated at compile time
(no reflection at runtime). `jk update` re-locks `"latest"` to a newer stable.
