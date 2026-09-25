# Dependencies

A version in `jk.toml` is an exact pin. `jk add g:a` writes today's stable as a number. `jk add g:a:1.2.3` pins that version. When a platform BOM already supplies it, the entry is `managed`.

`deps` applies and relocks in one call. Do not preview, then apply, then lock.

- add: `deps(action=add, coords=["g:a"])` or `coords=["g:a:1.2.3"]`
- remove: `action=remove` with the coordinate or the artifact name
- pin: `action=pin` with `g:a:version` rewrites that entry
- `scope`: `main` (default), `test`, `runtime`, `provided`, `processor`
- `preview=true` shows the edit and does not write or lock

CLI: `jk add` and `jk remove`. The reply is the `jk.toml` change, then `lock ok` or the resolve failure. After that, `run(kind=test)` does not need `run(kind=lock)`.

`java = N` is the language level (`--release`). Do not set `jdk = 17` or `jdk = 21` to emit older bytecode. `jk update` bumps pins on the same major. `jk update --major` crosses one. Do not hand-edit versions inside a BOM.
