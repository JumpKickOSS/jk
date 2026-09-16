# openapi-spring

A Spring Boot module whose API interface is generated from `api/openapi.yaml` by the `[openapi]`
preset ([Generate](../../generate.md)). `GreetingController` implements the generated
`GreetingsApi`; the `Greeting` model is generated too.

```bash
jk build          # generate-openapi runs openapi-generator-cli, then javac compiles the interface
jk explain        # the second build shows the generate step as a cache hit
jk run            # GET http://localhost:8080/greetings/Ada
```

Edit the spec — add a property, rename the operation — and the next build regenerates and the
compiler reports what no longer matches.
