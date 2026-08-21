# Container images

```bash
jk image
```

Daemonless OCI images (Jib-core). No Docker daemon required to *write* the image; a
container runtime is only needed for some [AOT training](#aot-cache-in-the-image) paths.

```toml
[image]
base = "docker.io/bellsoft/liberica-runtime-container:jre-25-slim-glibc"
# aot-cache = true
```

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

The cache is started once and checked before it becomes a layer.

Spring Boot images are unpacked into Boot’s CDS/AOT-friendly layout first (thin launcher
jar + `lib/`). A non-Boot exploded-classes layout remains unsupported: a CDS dump refuses
directory classpath entries.

App-local AOT without an image: [Build](build.md#jvm-startup-cache-app-aot--cds).

## Related

[Packaging](packaging.md) · [Native](native.md) · [Publish](publish.md)
