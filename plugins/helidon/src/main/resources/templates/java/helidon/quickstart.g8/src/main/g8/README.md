# $name$

The Helidon SE quickstart (Helidon 27, JDK 27): a `/greet` REST service, scaffolded from jk's
`helidon/quickstart` template, the counterpart of `helidon init`'s quickstart.

```bash
jk build     # target/$name$.jar + target/libs/, Helidon's deployable
jk test      # MainTest, on a free port, under the test config profile
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
curl -s localhost:8080/traced
$endif$
```

`[helidon] version` imports Helidon's BOM, so dependencies carry no version, and contributes
Helidon's annotation processors. `jk update` moves it to a newer Helidon.
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
