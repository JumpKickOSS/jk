# $name$

Micronaut HTTP service (compile-time DI, Netty server). Scaffolded from the jk
`micronaut` template — Micronaut platform $micronaut$.

```bash
jk build
jk test      # @MicronautTest with embedded server + HTTP client
jk run       # serve on :8080
curl -s 'localhost:8080/hello?name=jk'
```

The `micronaut-platform` BOM in `[platform-dependencies]` pins all Micronaut modules;
`[processor-dependencies]` wires `micronaut-inject-java` so bean definitions are
generated at compile time (no reflection at runtime). Bump the BOM version to upgrade.
