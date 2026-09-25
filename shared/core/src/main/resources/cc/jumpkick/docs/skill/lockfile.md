# Lockfile

`jk-lock.toml` is law. `jk build` and `jk test` do not re-resolve while the lock matches the manifests. Commit it. One lock per workspace, at the root.

`deps` rewrites `jk.toml` and relocks. The reply is what changed, then `lock ok` or the resolve failure. Do not call `run(kind=lock)` before `run(kind=test)` after `deps`.

`jk lock` rewrites the lock and keeps pins. `jk update` moves declared pins to the newest stable on the same major, then relocks. A missing `manifests-sha256`, or one that does not match the manifests, means the lock is stale.

A resolve failure is the verdict. Fix the coordinate or the repository. Read `why(coord)` for the path and the rule that picked the version. Do not delete the lock to skip a conflict.
