# Wrapper

```bash
jk wrapper                     # emit ./jk + jk.bat bootstrap scripts
jk wrapper update              # refresh the committed scripts
```

The wrapper is a **bootstrapper, not a pin**. It runs, in order:

1. The installed `jk` (`$JK_HOME/bin`, default `~/.jk/bin`; the native client or the JVM
   client's launcher) when it satisfies the `jk-min` floor of the nearest `jk-lock.toml` at or
   above the wrapper.
2. Otherwise any other `jk` on `PATH`, which enforces the floor itself.
3. Otherwise the latest release, installed once into `$JK_HOME` (verified against the signed
   `SHA256SUMS`, then its exact artifact digest).

Only one wrapper downloads at a time per `JK_HOME`. Others started meanwhile wait on
`bin/.jk-wrapper.lock` and then run what it installed. A lock whose owner process is gone, or
that is older than ten minutes, is broken.

The release decides which client the wrapper installs, as it does for the installers: the native
client when the release's `SHA256SUMS` lists one for this OS and architecture, otherwise the
[JVM client](install.md#the-jvm-client) on the JDK 25+ named by `JK_JAVA_HOME`, `JAVA_HOME` or
`java` on `PATH`. `JK_CLIENT=jvm` or `JK_CLIENT=native` chooses explicitly.

Unix wrappers require OpenSSL; Windows wrappers use the RSA implementation built into
PowerShell 5.1.

Team version alignment belongs to the installer / CI images, never to a lock row.
`jk update` bumps **dependency** pins; `jk self update` updates **the tool** — two
deliberate steps.

## Related

[Install](install.md) · [Lockfile](lockfile.md)
