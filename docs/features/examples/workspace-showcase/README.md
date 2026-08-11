# Workspace showcase

Minimal multi-module JumpKick workspace for CI and human dogfood (ticket-1038).

```text
workspace-showcase/
  jk.toml          # [workspace] modules = ["lib", "app"]
  lib/             # library
  app/             # depends on lib via workspace = true
```

## Smoke (reinstalled `jk`)

```bash
# from repo root, after bootstrap:
./gradlew clean dist installLocal && ./install.sh build/dist/jk
export PATH="$HOME/.jk/versions/0.12.0/bin:$PATH"   # or ~/.local/bin

cd docs/features/examples/workspace-showcase
jk lock
jk build
jk test --modules app     # select modules with tests (root does not cascade tests)
jk build --modules app    # selector smoke
```
