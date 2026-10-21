# Engine

Every `jk` command talks to one resident engine per state dir; the first command starts it. Do not `kill` it, hunt its JVM with `ps`, or restart it to clear a build: `-r` redoes the work, `-F` also re-fetches.

- `jk engine status`: this directory's engine, then every engine you run (id, pid, uptime, jobs). `-O jsonl` has the fields, `mcpUrl` among them.
- `jk engine stop`: this directory's engine, after its jobs finish. `--now` abandons them. `--all` stops every engine this install started, under any `JK_STATE_DIR`. `--pid N` stops one engine `status` lists.
- The log is `<state>/engine/<id>.log` (`~/.jk/state` by default). `jk doctor` checks the engine, cache, JDKs and workers.
- After `jk install` or an upgrade, the next command moves to the new engine. Nothing to restart.
- An absolute `JK_STATE_DIR` gives a sandbox its own engine.
