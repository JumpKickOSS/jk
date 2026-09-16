# Generate

Code generators — OpenAPI Generator, jOOQ, Avro, ANTLR, JAXB `xjc`, anything with a `main` — run
as **cached steps in the generate stage**: declared inputs are the cache key, the output joins the
compiler's source set (or the resources), and `jk explain` shows the step like any other. One
worker runs every generator; the `[generate]` table drives it directly and preset tables such as
`[openapi]` are sugar over it.

## `[generate.<name>]` — any JVM tool

```toml
[generate.api]
tool     = "org.openapitools:openapi-generator-cli:7.11.0"   # Maven coordinate
main     = "org.openapitools.codegen.OpenAPIGenerator"       # optional: default is the jar's Main-Class
inputs   = ["api/openapi.yaml"]                              # module-relative files or globs
args     = ["generate", "-i", "${in}", "-g", "spring", "-o", "${out}", "--api-package", "com.acme.api"]
contributes = "sources"                                      # sources | resources
```

| Key | Meaning |
|---|---|
| `tool` | The generator's coordinate. A bare version is an exact pin; `^`/`~` float within the line; `latest` is the newest stable. The tool's runtime closure is fetched with it, so a tool that is not a fat jar still runs. |
| `main` | The class to run. Omitted, jk reads `Main-Class` from the tool's own jar. |
| `inputs` | What the tool reads: module-relative paths or globs (`src/main/avro/**/*.avsc`). Required; a pattern matching nothing fails the step. |
| `args` | The tool's arguments. `${in}` is the first input, `${inputs}` all of them (alone, one argument per input; embedded, joined with the path separator), `${out}` the output directory, `${module.dir}` the module root — every one an absolute path. Other `${…}` pass through to the tool. |
| `contributes` | `sources` (default) folds the output into the compiler's source set; `resources` into the packaged resources. `test-sources` is not available yet. |
| `out` | The output directory's name under the step's output root; default `generated/<name>`. |

Each entry is one step named `generate-<name>`. Its **action key** is the input files' content
(a glob's whole base directory), the entry's config, the tool's jar hashes, the build JDK and the
worker itself — so a changed spec, argument, tool version or JDK re-runs the generator, and
nothing else does. An unchanged entry is restored from the cache.

The tool runs in a **forked JVM** on the build's JDK, never in the engine, with `${out}` as its
working directory: what it writes by relative path lands in the output; what it reads, jk hands it
by absolute path. Its output is captured; lines of the form `path:line[:col]: message` become the
step's diagnostics with a location, an error when the tool failed or said `error`, a warning
otherwise. A non-zero exit fails the step with the tool's last lines.

## `[openapi]` — the OpenAPI Generator preset

```toml
[openapi]
spec      = "api/openapi.yaml"   # default api/*.yaml (the first match)
generator = "spring"             # any openapi-generator generator: spring, java, kotlin-spring, …
package   = "com.acme.api"       # the root: api + invoker package; models in <package>.model. Default <group>.api
version   = "latest"             # openapi-generator-cli release; default latest, a bare version is exact
options   = { useTags = "false" }  # --additional-properties, over the preset's defaults
# api-package = "com.acme.api.controller"   # each replaces the name the root derives
# model-package = "com.acme.dto"
# invoker-package = "com.acme.api.client"
```

The preset expands to one generator entry — `openapi-generator-cli generate -i <spec> -g
<generator> -o <out> --api-package … --model-package … --additional-properties …` — and `jk
explain` shows it as the step `generate-openapi`. `package` is the root every generated package
derives from; `api-package`, `model-package` and `invoker-package` each replace the derived name
when a code base keeps them apart (api under `<root>.api`, say, with models under `<root>.model`). For `spring` the defaults produce an
interface-only API a Boot module compiles with no extra libraries: `interfaceOnly`,
`useSpringBoot3`, `useJakartaEe`, no documentation provider or annotation library, no
`JsonNullable`, `useTags`. Any of them is overridden by `options`; another generator gets only
what `options` says.

The worked example: [`examples/openapi-spring`](examples/openapi-spring/) — a Boot controller
implementing the generated interface. `jk import` writes this table from a POM's
`openapi-generator-maven-plugin` ([Migration](migration.md#which-maven-plugins-import-and-how-well)).

## Beside a framework table

A module runs every plugin whose table it declares, each in its own worker, so `[generate]` or a
preset sits beside `[spring-boot]`, `[quarkus]`, `[micronaut]`, `[protobuf]` or `[android]` in one
module: the generator contributes its step, the framework packages the artifact.
[`examples/openapi-spring`](examples/openapi-spring/) is that shape. What a module cannot hold is
two tables that both package its main artifact (`[spring-boot]` and `[quarkus]`); the build
refuses that pair by name ([Using plugins](plugins.md#more-than-one-plugin-in-a-module)).

## What the lock pins

The tool coordinate you write is the pin: a bare version is exact and costs no network; a
floating selector resolves against the tool's own `maven-metadata.xml` at fetch time. The tool's
transitive closure resolves from the tool's POMs into a content-addressed directory whose hash is
in the step's action key, so a closure that changes moves the key.

## Presets to come

`[jooq]`, `[avro]`, `[antlr]` and `[jaxb]` follow the same shape — a manifest with a schema and a
tool coordinate, a small expansion into a generator entry. Until they land, each tool works through
`[generate]` today.

## Related

[Explain](explain.md) · [Plugins](plugins.md) · [Frameworks](frameworks.md)
