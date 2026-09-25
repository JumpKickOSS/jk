# Workspaces

The root `jk.toml` lists `[workspace] modules`. One `jk-lock.toml` lives at that root. Members inherit identity and do not get their own lock.

From a member directory, `jk build` and `jk test` mean that module plus its upstreams. `only` or `-m api,worker` selects modules. Independent modules build together (`-j`; the default is all effective cores). `-w` is within-module test workers, a separate knob.

Outputs: `{workspace}/target/{module-rel}/`. A standalone project uses `{project}/target/`.

`deps` edits the module whose `dir` you pass. A short-name catalog is `jk-libs.toml` at the root only.
