# Dynamic surface: keep rules and reachability metadata

R8 and GraalVM `native-image` both need to be told about the parts of a program that static
analysis cannot see — reflection, JNI, proxies, resources, serialization, service loading. jk
calls that the **dynamic surface**, models it once, and emits it in both tools' formats.

This document is the design the implementation follows.

## The pipeline

```
   1. derive     from the inputs' own by-name indexes          no run, no config
   2. compose    library keep rules + META-INF/native-image     no run, no config
   3. train      jk train — observe a real run                  opt-in, explicit
   4. declare    the user's own rules                           always last
                             │
                             ▼
                      DynamicSurface
                        ╱          ╲
              keeps.pro              reachability-metadata.json
                  │                            │
              minified jar             native-image
```

Each stage is narrower and more expensive than the one above it. Derivation and composition are
free and cover the mechanical majority; training covers what is left; the user covers what
nothing can observe. **Consumers only consume** — neither the minified packager nor the
native-image driver ever runs a recorder.

### Training primarily targets native-image

The two emitters are not symmetric in what training is worth. Derivation and composition serve
both consumers; `jk train` exists for `native-image` and the JVM AOT cache — but its merged
surface (reflection / resource kinds) also feeds the minified packager's keep rules when
`target/train/merged/dynamic-surface.json` is present, and the surface file participates in the
minified jar's action key.

R8 in `--classfile` mode always runs in full mode, and there a class keeps its generic signature
only if the class is explicitly kept — see [Generic-reflection is its own kind](#generic-reflection-is-its-own-kind) below. An
application that resolves types by runtime generic matching therefore cannot be shrunk at *any*
keep setting, so there is no observation a recorder could make that would fix it. For generic-reflection
apps, training cannot rescue R8 — the by-name and resource observations it can contribute are a
bonus, not the reason to run it.

The minified jar stays an advanced opt-in for applications that do not work that way, fed by
derivation, composition and hand-written `[minified] keep` rules.

### `jk build` never trains

Training runs an application under an agent. It is slow, it needs a working runtime environment,
and its completeness is a property of what the run exercised. None of that belongs in an implicit
build step. `jk train` is a verb the user types.

## Decisions

| Topic | Decision |
|---|---|
| CLI | `jk train`. A verb, not a flag: one observed run feeds both the reachability metadata and the JVM AOT cache, so hanging it off `jk native` would misplace it |
| Build target | `train`, matching the verb. Not part of `build`, `test`, `assemble` or `native` |
| Suite discovery | JUnit `@Tag("train")` by default; `[train] command = "…"` for apps whose exercise is not a test suite |
| Multi-profile | `[[train.profile]]` tables — `name`, `env`, `properties`, `args`. Each runs the suite; results union |
| Recorder | GraalVM tracing agent in v1, behind a `Recorder` interface |
| Output | `target/train/raw/<profile>/` and `target/train/merged/`; promote with `[train] commit-to` |
| Emit | Graal unified `reachability-metadata.json` from the train run; ProGuard text for the minified jar from derive + compose + user rules |
| Precedence | derive → compose → train → user; positive entries union, user directives land last |
| Fingerprint | lockfile digest, module classes, train suite sources, profile definitions, jk version, recorder version |
| Strict mode | `[train] require-fresh = true`, or `--require-train-fresh` |

## The model

`DynamicSurface` is a versioned, mergeable record of dynamic entry points. Merging is a union
with deterministic ordering, so two profiles or two sources combine without a precedence fight.

| Kind | R8 emits | native-image emits |
|---|---|---|
| reflective type | `-keep class <fqcn>` | reflection entry |
| reflective member | `-keep class <fqcn> { <member>; }` | reflection entry with member |
| generic-reflection type | `-keep class <fqcn> { *; }` | *nothing* |
| proxy interface set | `-keep interface …` per interface | proxy entry |
| resource | `-keep` not applicable; resource pass-through | resource pattern |
| serialization type | `-keepclassmembers` for the serialization contract | serialization entry |
| service implementation | `-keep class <fqcn> { *; }` | reflection entry |
| JNI surface | `-keep class <fqcn> { *; }` | JNI entry |

The rows where the two columns disagree are why an intermediate model earns its place. A format
converter could not express them: the same observation means different things to the two
consumers.

### Generic-reflection is its own kind

In `--classfile` mode R8 always runs in full mode, and there a class's generic signature survives
**only if the class is explicitly kept**. `-keepattributes Signature` does not do it, and neither
does `-keepattributes **`. A framework calling `Class.getTypeParameters()` on a type R8 merely
retained sees zero parameters.

native-image needs nothing here — Graal preserves generic signatures without being asked. So the
kind exists solely to make the R8 emitter produce a class-level keep, and the native emitter
ignore it.

**Known recorder gap.** The Graal tracing agent records reflection API calls, and
`Class.getTypeParameters()` is not part of the metadata schema it writes. Populating this kind
therefore needs either a supplementary recorder or a hand-written rule. v1 ships the kind and the
emitter; filling it automatically is a follow-up, and the docs say so rather than implying
coverage that does not exist.

## Deriving from by-name indexes

Two conventions carry class names as text, so nothing in the bytecode references them:

| convention | shape |
|---|---|
| service files | `META-INF/services/<interface>` — one FQCN per line |
| marker indexes | `META-INF/<vendor>/<interface>/<impl>` — the leaf path segment *is* the class name |

Both are read straight out of the program inputs, exactly, with no run and no configuration. On a
Micronaut application this is ~320 classes, roughly a fifth of which match no naming convention —
framework internals, and the SLF4J provider whose loss silences the logging that would report
everything else. Every class derived here is one the train suite does not have to exercise.

Recognition is strict: every dot-separated segment must be a legal Java identifier, so data files
sitting beside an index are not mistaken for classes.

## Composing published library metadata

Libraries describe their own reflective surface under `META-INF/native-image/<group>/<artifact>/`,
in either the split schema (`reflect-config.json`, `resource-config.json`, `proxy-config.json`,
`serialization-config.json`, `jni-config.json`) or the unified `reachability-metadata.json`. jk
reads both.

`native-image` finds this on the classpath by itself, so composition exists for R8's benefit: the
same declarations become keep rules. On a Micronaut application it is ~220 entries that would
otherwise have to be trained for or hand-written.

A malformed file is skipped rather than failing the build — a third party's broken metadata
should not stop someone packaging their application. `native-image.properties` is build flags
rather than surface, and is not jk's to interpret.

## `jk train`

```bash
jk train                      # every declared profile
jk train --profile prod       # one
```

The suite is the application exercised as a user would exercise it. Unit tests are not enough and
the docs must not imply otherwise: they exercise units in isolation, which is close to the
opposite of what a whole-application observation needs.

Because the runner owns starting the application and shutting it down, a server is trainable — and
the same execution can record a JVM AOT cache (JEP 514 on JDK 25+), covering the request paths the
workload drives rather than startup alone.

```toml
[train]
# command = "./scripts/smoke.sh"      # when the exercise is not a JUnit suite
# commit-to = "src/train/metadata"    # promote merged output into the repo
# require-fresh = false
# aot-cache = true                    # also record a JVM AOT cache from the same run

[[train.profile]]
name = "default"

[[train.profile]]
name       = "prod"
properties = { "micronaut.environments" = "prod" }
env        = { DATABASE_URL = "jdbc:h2:mem:train" }
```

### Output layout

```
target/train/
  raw/<profile>/          recorder output, one dir per profile
  merged/
    dynamic-surface.json  the model
    keeps.pro             R8
    reachability/         native-image configuration directory
  fingerprint             what the outputs were produced from
```

`target/` is disposable, so `commit-to` copies `merged/` into the repository for teams that want
metadata reviewed and versioned rather than regenerated in CI.

### Freshness

The fingerprint covers the lockfile digest, the packaged main jar, the profile definitions, the
profiles a filtered run actually observed, and the jk version (which pins the recorder wiring).
Suite sources and classes are covered through the jar and lock they produce, not hashed
directly. Any change makes the outputs stale.

Stale outputs are used with a warning by default — a metadata set that is slightly behind is
usually better than none. `require-fresh = true` turns that into an error, which is the release
bar: a project can run PRs without training and still refuse to ship a binary built from
metadata that no longer matches the code.

## CI

| Job | Runs | Command |
|---|---|---|
| PR | every push | `jk test` — no agent, no train |
| Train | main, nightly, or a path filter | `jk train`, then upload or commit `target/train/merged/` |
| Release | tag | `jk build` with `require-fresh = true` |

Training on every PR would be slow, non-hermetic, and would make a coverage lottery look like a
guarantee.

## What this does not give you

Observation is not proof. A path the train run did not exercise is a path the recorder did not
see, and R8 or `native-image` will remove it. That is the same failure mode as writing no rules
at all, arriving later and with more confidence attached.

Concretely, out of scope for any amount of training:

- **Code paths the suite misses.** An error handler, an admin endpoint, a rarely-taken branch.
  Coverage of the train suite is the honest measure of how complete the metadata is.
- **Framework AOT.** Spring Boot, Quarkus and Micronaut have their own build-time processors.
  jk composes with them; it does not replace them.
- **Generic-reflection discovery**, until a recorder can see it — see above.

The reliable artifact remains the fat jar (`assembly = true`). Shrinking and native-image are
opt-in paths for teams that will own their metadata.

## See also

- [Packaging matrix](packaging.md) — where shrink and the by-name index audit are configured
- [Build plan](build-plan.md) — targets, tasks and stages
