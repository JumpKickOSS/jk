# Container images

```bash
jk image
```

Daemonless OCI images (Jib-core). No Docker daemon required to *write* the image; a
container runtime is only needed for some [AOT training](#aot-cache-in-the-image) paths.

```toml
[image]
# base      = "eclipse-temurin:{java-major-version}-jre"   # default: a JRE of the module's java
# name      = "gyggly-app"        # image repository; default the module's artifact id
# registry  = "ghcr.io/acme"      # push target; default a local daemon load
# tag       = "edge"              # default the module version
# aot-cache = true

[image.env]
# JAVA_OPTS = "-Xmx256m -XX:+UseSerialGC"   # the JVM flags: entrypoint AND AOT training run
```

The image is `<registry>/<name>:<tag>`, each part falling back as noted. Layers, from least to most
volatile: the base, this module's **runtime dependency jars** at `/app/libs/<artifact>-<version>.jar`
— locked releases in one layer, then `-SNAPSHOT` versions and workspace siblings' thin jars in a
volatile layer, so rebuilding a sibling never rewrites the release layer — then the application jar
at `/app/classpath/`. In a workspace that closure is the module's own — its declared externals, its
sibling modules' externals, and the siblings' thin jars — exactly what `jk build`'s fat jar nests,
never the whole workspace lock. The entrypoint is
`java <JAVA_OPTS…> -cp /app/classpath/*:/app/libs/* <main>`.

The release layer is kept in the cache root's `image-layers/`, keyed by the sha256 of each jar it
holds and the path it ships at, so the next `jk image` of the same releases uses it as built
instead of compressing the jars again. The snapshot and application layers are built every time:
a rebuilt jar under its old name always ships its new bytes.

A Quarkus application ships its own `quarkus-app/` tree instead, in two layers: its `lib/`, then
the rest (`quarkus-run.jar`, `app/`, `quarkus/`). The `lib/` layer is kept as the release layer is,
by the sha256 of each jar, so an image after a code edit, which rewrites the whole tree, ships the
dependencies as built and compresses only the application again. The rest is built every time.

With no `base`, the image is built on `bellsoft/liberica-runtime-container:jre-<N>-slim-glibc`,
where `<N>` is the module's Java level: its `java`, else its `jdk` major, else the major of the JDK
that compiles it. The templates write no base, so a project made at `java = 27` gets a JRE 27 and
one at 21 a JRE 21. A `base` you write may say `{java-major-version}` for that level. A base
whose reference names a Java older than the level (`eclipse-temurin:21-jre` for a `java = 25`
module, `gcr.io/distroless/java21-debian12`, `…:jre-21-slim-glibc`) is refused, since its JVM
cannot load the module's classes; a base whose Java the reference does not say (`latest`, a
digest alone, a company image tagged with its own version) is taken as written.

A module with [`[application] lib-dir`](packaging.md#lib-dir-layout) ships that layout instead: the
same dependency layers under `/app/<lib-dir>/`, named and filtered as the lib directory is, then
`/app/<name>.jar` in its own layer, and `java <JAVA_OPTS…> -jar <name>.jar` from `/app` as the
entrypoint. It replaces a Dockerfile that copies `target/<name>.jar` and `target/libs`. With
`aot-cache = true` the trained layout below ships instead.

`JAVA_OPTS` is the one JVM-flag hook. Its tokens lead the entrypoint and, when `aot-cache = true`,
every training run as well: a cache is only valid for the collector and heap shape it was trained
with, so the two cannot be allowed to differ.

## Private registries

Both registry legs authenticate: the **base-image pull** (every mode — tarball, daemon and
push) and the **push** itself. Credentials are looked up by the registry **host**, through the
same chain `jk publish` uses:

```bash
echo "$GITHUB_TOKEN" | jk repo login ghcr.io -s "$GITHUB_USER"
export JK_REPO_GHCR_IO_USERNAME=… JK_REPO_GHCR_IO_PASSWORD=…   # or JK_REPO_GHCR_IO_TOKEN
```

Order: environment → `jk repo login` store → `~/.m2/settings.xml` server of that id → forge
token. The id being the host is what binds the credential to that registry (see
[Repositories § Credentials](repositories.md#credentials)). Whatever that finds is tried first; an existing **`docker login`** is the fallback, so
`~/.docker/config.json`, its `credHelpers`, and the well-known cloud helpers (`gcloud`,
`ecr-login`, ACR) keep working with no jk-side setup.

A token with no user name is sent as the password with a placeholder user name, which is what
registries that issue opaque tokens expect. A registry that reads the user name — Docker Hub, a
Harbor robot account — needs the `--username` form.

Dockerfile mode (`image.docker-file`) is unaffected: it shells out to `docker build` /
`docker push`, which read `~/.docker/config.json` themselves. It shares that file with Jib mode
but not the jk-side sources above.

A registry on `localhost` / `127.0.0.1` is reached over plain HTTP, the same default docker and
podman apply.

## SBOM and provenance on a push

A push attaches two documents to the image it pushed, as OCI 1.1 referrers whose subject is the
image's digest:

- the module's **CycloneDX SBOM** (`application/vnd.cyclonedx+json`), the same bill of materials an
  application jar embeds, read from the lock; a copy stays at `target/sbom/image.cdx.json`.
- an unsigned **SLSA v1 provenance** statement (`application/vnd.in-toto+json`) naming the image
  digest, the base image and every locked dependency with its SHA-256.

```bash
jk image --registry ghcr.io/acme
oras discover ghcr.io/acme/app:1.0.0     # lists the SBOM and the provenance
```

A registry that serves the referrers API indexes them itself. For one that does not (a
`registry:2`), JumpKick keeps the index at the tag `sha256-<image digest hex>`, the fallback the
distribution spec names. They are written with the push credential. If the registry refuses them,
the image stays pushed and the build prints a notice. A tarball or a daemon load attaches nothing,
and neither does a node module's image or Dockerfile mode.

## In a workspace

`jk image` from a member's directory builds that member's image. The member's `[image]` table
sits over the workspace root's, which sits over the user-global table in `~/.jk/config.toml`; the
nearest value wins per key. The root answers for what is a workspace fact — `base`, `user`,
`registry`, `tag`, `env`, `labels`, `platforms`, `docker-executable`, `aot-cache` — so one
`[image]` on the root names the registry and the base image for every member, and a member sets
only what differs. What names one image never flows down: `name`, `main`, `ports` and
`docker-file` are read from the member alone, so two members cannot inherit one image name and
push over each other. `env` and `labels` merge key by key, the member's entry winning.

```toml
# jk.toml (workspace root)
[image]
base     = "docker.io/bellsoft/liberica-runtime-container:jre-{java-major-version}-slim-glibc"
registry = "ghcr.io/acme"
labels   = { team = "core" }

# svc/api/jk.toml
[image]
ports = [8080]          # image ghcr.io/acme/api:<version>, on the root's base, labelled team=core
```

## Node modules

A node module with a server output (a `[node] start`, or one its framework implies: Next
standalone, Nuxt, SvelteKit with adapter-node, React Router, a plain `start` script) gets a
Node.js image:

| Layer | Holds | Path |
|---|---|---|
| `dependencies` | the production dependencies, installed with the locked package manager (`npm ci --omit=dev`, `pnpm install --prod`, `yarn workspaces focus --production`, `bun install --production`); left out when the output carries its own (Next standalone, Nitro's `.output`) or `package.json` has none | `/app/node_modules` |
| `app` | the server output and `package.json` (a plain server: the module's files, without `node_modules` and `target/`) | `/app` |

The `dependencies` layer is kept in the cache root's `image-layers/` by each file's path, size,
times and file id, so an image whose production install has not run again
reuses it as built. A tree with a file written in the last two seconds is built afresh and not
kept. The `app` layer is built every time.

The base is `gcr.io/distroless/nodejs<major>-debian13` for the locked Node.js major, the
entrypoint `node` with the start command's arguments (`/nodejs/bin/node` on distroless), run from
`/app` as user `65532` with `PORT=3000`, `HOSTNAME=0.0.0.0` and `NODE_ENV=production`, port 3000.
Every one of those is a default `[image]` overrides. A start command that runs a package's bin
(`react-router-serve …`) runs that bin's script under `node`.

A node module whose output is static files has no image of its own: its bundle rides in the image
of the JVM module that serves it. `[image] kind = "static"` gives it an nginx image instead —
`nginx:stable-alpine`, the output at `/usr/share/nginx/html`, port 80, and a generated
`default.conf` that answers unknown paths with `index.html` and caches the hashed `assets/` for a
year.

A node module takes the workspace root's `[image]` registry, tag, labels, env and platforms, but
not its `base` (a JRE) or `aot-cache`, which trains a JVM and is refused on a node module.

```toml
# web/jk.toml
node = 24

[image]
kind = "static"   # omit for a server; a static module without it has no image
```

## AOT cache in the image

```toml
[image]
aot-cache = true
```

Trains a JEP 514 AOT cache and ships it as an image layer, with the entrypoint pointing at
it. Opt-in: it costs a training run at build time and tens of MiB of image.

The trained tree keeps the layers an image without a cache has, and the cache comes last: release
dependency jars, then `-SNAPSHOT` and sibling jars, then the application, then `app.aot`. A code
change re-pushes the application's layer and the cache's; the release layer stays. The layering
cannot break the cache: what the JVM checks of each classpath entry is its path, its size and its
modification time, and every file ships at the path and with the time it was trained at, whichever
layer carries it.

The cache is only valid for the **exact JVM build** that produced it, so JumpKick trains
with the **image’s own JVM**, never the build JDK:

- **Host** (Linux, matching the image architecture): unpack the base image JRE from layers
  Jib already pulled and run it directly. No container runtime.
- **Container:** otherwise train inside the base image (docker, podman, or nerdctl), run for the
  image's platform (`--platform`, linux/amd64 unless `platforms` names another). On a host of
  another architecture (an arm64 Mac building the default amd64 image) the runtime emulates it:
  the training runs are slower (on an M-series Mac with Podman, a Helidon server's image step took
  about 35 s instead of 21 s), and the cache leaves out AOT code, which would be compiled for the
  emulator's CPU rather than the one the image runs on.

When the image cannot carry a cache that its JVM would load, it ships without one and the build
says so on its result, a passing one included: a host whose runtime cannot run the image's
platform, or an image built for several `platforms` (one cache layer cannot serve two JVMs).

The training run must reach a steady state on its own: an application that exits with a failure
before it settles (a missing database, a bad flag) fails the build with its last lines, rather than
shipping a cache that describes a process which never started. The cache is then started once
with `-XX:AOTMode=on`, so a JVM that would not map it refuses to start, and only then becomes a
layer.

The training runs see the image's `[image.env]`, with `[image.training-env]` laid over it, and
the image's `JAVA_OPTS`. A container runtime reads them from an owner-only `--env-file` under the
module's `target/`, deleted once the runs are over, so no value shows in the host's process list.
A value such a file cannot carry intact (a line break, trailing whitespace) is named with `-e` and
handed to the runtime in its own environment instead.
`training-env` reaches the training runs alone, never the image: it is how an application that
needs a database or a broker at startup trains without one. The cache
depends on the JVM's flags, which stay the entrypoint's, so the image still uses it. For Spring
Boot, skipping the migrations and exiting once the context has refreshed trains the whole startup:

```toml
[image.training-env]
APP_MIGRATE_ON_START = "false"      # read by the app at run time: no database during the build
SPRING_CONTEXT_EXIT  = "onRefresh"  # start every bean, then exit: the run settles on its own
```

With `[spring-boot] aot = true`, Spring's AOT processing fixes `@ConditionalOnProperty` and friends
when it generates the context, so `SPRING_FLYWAY_ENABLED=false` no longer removes Flyway at run
time; switch the migration with a value the app reads while it runs (a `FlywayMigrationStrategy`
that checks it), as jk-examples' hotel application does.

Spring Boot images are unpacked into Boot’s CDS/AOT-friendly layout first (thin launcher
jar + `lib/`), whose jars ship in the same dependency layers as above. A non-Boot
exploded-classes layout remains unsupported: a CDS dump refuses directory classpath entries.

App-local AOT without an image: [Build](build.md#jvm-startup-cache-app-aot--cds).

## When the worker itself fails

`jk image` runs in the `jk-image-builder` worker, on a classpath the engine rebuilds from the
worker's POM at launch. If every mode fails inside Jib before it touches your image — a
`NoSuchMethodError` in `com.google.cloud.tools.jib`, a `NoClassDefFoundError` — the worker is
running on the wrong jar, not your project. `jk doctor -v` shows which store repo the worker
came from and every entry on its launch classpath; `jk storage clean -w` drops the
installed workers so the next `jk image` fetches the published plugin again.
See [Cache](cache.md#plugin-workers).

## Related

[Packaging](packaging.md) · [Native](native.md) · [Publish](publish.md)
