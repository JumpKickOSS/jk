# Workspaces

The root `jk.toml` lists `[workspace] modules`. One `jk-lock.toml` lives at that root. Members inherit identity and do not get their own lock.

From a member directory, `jk build` and `jk test` mean that module plus its upstreams. `modules` on `run` or `-m api,worker` selects modules. Independent modules build together (`-j`; the default is all effective cores). `-w` is within-module test workers, a separate knob.

Outputs: each module's own `target/`, in Maven's layout.

A member with `[publish] skip = true` (tests, examples, tools) builds and tests, but `jk publish` and `jk install` leave it out; a shipped member may name it only in test tables.

`deps` edits the module whose `dir` you pass. A short-name catalog is `jk-libs.toml` at the root only.
