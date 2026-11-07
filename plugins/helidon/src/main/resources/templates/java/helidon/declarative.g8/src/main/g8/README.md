# $name$

Helidon Declarative (Helidon 27, JDK 27): the quickstart's `/greet` REST service as an annotated
endpoint (`GreetEndpoint`), scaffolded from jk's `helidon/declarative` template. Helidon's code
generators write its routing and service descriptors at compile time, and the service registry
starts the web server with it. Declarative is a preview feature in Helidon 27.

```bash
jk build     # target/$name$.jar + target/libs/, Helidon's deployable
jk test      # MainTest: the registry's server on a free port, under the test config profile
jk run       # serve on :8080
jk dev       # serve, and restart on every change
curl -s localhost:8080/greet
curl -s localhost:8080/greet/Joe
curl -s -X PUT -H 'Content-Type: application/json' -d '{"greeting":"Hola"}' localhost:8080/greet/greeting
$if(health.truthy)$
curl -s -i localhost:8080/observe/health
$endif$
$if(metrics.truthy)$
curl -s localhost:8080/observe/metrics
$endif$
$if(tracing.truthy)$
curl -s localhost:8080/greet     # traced: @Tracing.Traced on GreetEndpoint
$endif$
```

`[helidon] version` imports Helidon's BOM, so dependencies carry no version, and `declarative =
true` contributes every Declarative code generator (`helidon-bundles-apt`). `jk update` moves it to a newer Helidon.
$if(docker.truthy)$

`jk image` builds the container image: the jar and `libs/` as layers, started with `java -jar`,
what Helidon's `Dockerfile` does.
$endif$
$if(jlink.truthy)$

`jk build` also writes `target/$name$-jri/`, a runtime image with an AOT cache: run it with
`target/$name$-jri/bin/start`.
$endif$
$if(k8s.truthy)$

`app.yaml` deploys the image to Kubernetes: `kubectl create -f app.yaml`.
$endif$
