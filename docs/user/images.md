# Container images

```bash
jk image
```

Daemonless OCI images (Jib-core). No Docker daemon required to *write* the image; a
container runtime is only needed for some [AOT training](#aot-cache-in-the-image) paths.

```toml
[image]
base = "docker.io/bellsoft/liberica-runtime-container:jre-25-slim-glibc"
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

`JAVA_OPTS` is the one JVM-flag hook. Its tokens lead the entrypoint and, when `aot-cache = true`,
every training run as well: a cache is only valid for the collector and heap shape it was trained
with, so the two cannot be allowed to differ.

## Private registries

Both registry legs authenticate: the **base-image pull** (every mode — tarball, daemon and
push) and the **push** itself. Credentials are looked up by the registry **host**, through the
same chain `jk publish` uses:

```bash
echo "$GITHUB_TOKEN" | jk repo login ghcr.io --username "$GITHUB_USER"
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

## AOT cache in the image

```toml
[image]
aot-cache = true
```

Trains a JEP 514 AOT cache and ships it as an image layer, with the entrypoint pointing at
it. Opt-in: it costs a training run at build time and tens of MiB of image.

The cache is only valid for the **exact JVM build** that produced it, so JumpKick trains
with the **image’s own JVM**, never the build JDK:

- **Host** (Linux, matching the image architecture): unpack the base image JRE from layers
  Jib already pulled and run it directly. No container runtime.
- **Container:** otherwise train inside the base image (docker, podman, or nerdctl).

The training run must reach a steady state on its own: an application that exits with a failure
before it settles (a missing database, a bad flag) fails the build with its last lines, rather than
shipping a cache that describes a process which never started. The cache is then started once and
checked before it becomes a layer.

Spring Boot images are unpacked into Boot’s CDS/AOT-friendly layout first (thin launcher
jar + `lib/`). A non-Boot exploded-classes layout remains unsupported: a CDS dump refuses
directory classpath entries.

App-local AOT without an image: [Build](build.md#jvm-startup-cache-app-aot--cds).

## When the worker itself fails

`jk image` runs in the `jk-image-builder` worker, on a classpath the engine rebuilds from the
worker's POM at launch. If every mode fails inside Jib before it touches your image — a
`NoSuchMethodError` in `com.google.cloud.tools.jib`, a `NoClassDefFoundError` — the worker is
running on the wrong jar, not your project. `jk doctor -v` shows which store repo the worker
came from and every entry on its launch classpath; `jk storage clean --workers` drops the
installed workers so the next `jk image` fetches the published plugin again.
See [Cache](cache.md#plugin-workers).

## Related

[Packaging](packaging.md) · [Native](native.md) · [Publish](publish.md)
