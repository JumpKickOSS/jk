# openapi-spring

A Spring Boot module whose API interface is generated from `api/openapi.yaml` by the `[openapi]`
preset ([Generate](../../generate.md)), with `[spring-boot]` beside it in the same module: the
generator contributes the `generate-openapi` step, Boot owns the platform BOM and packages the
jar. `GreetingController` implements the generated `GreetingsApi`; the `Greeting` model is
generated too, and both compile into the Boot jar.

```bash
jk build          # generate-openapi runs openapi-generator-cli, javac compiles the interface, boot-jar packs it
jk explain        # the second build shows the generate step as a cache hit
jk run            # GET http://localhost:8080/greetings/Ada
```

Edit the spec — add a property, rename the operation — and the next build regenerates and the
compiler reports what no longer matches.
