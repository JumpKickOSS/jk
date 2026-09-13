# Examples

Small in-tree samples used from the user docs. Larger adopter scenarios live in
[JumpKickOSS/jk-examples](https://github.com/JumpKickOSS/jk-examples).

| Sample | Shows |
|--------|--------|
| [assembly-app](assembly-app/) | Fat jar (`[application] assembly`) |
| [minified-cli](minified-cli/) | R8 minified jar |
| [checkstyle-recipe](checkstyle-recipe/) | Lint via `jk tool install` / `jk tool run` |
| [line-count-build](line-count-build/) | `jk/` / `.jk/` generate step |
| [workspace-showcase](workspace-showcase/) | Tiny two-module workspace |
| [vite-sidecar](vite-sidecar/) | `jk dev` running a Vite dev server beside the JVM (`[dev.sidecars]`) |

Each sample commits its `jk-lock.toml` (`vite-sidecar` its `web/package-lock.json` too), and the nightly builds every sample and fails if a
build rewrote its lock — lockfile-as-law, demonstrated. The one pre-1.0 exception: a sample
that pins a first-party plugin (`minified-cli` → `cc.jumpkick:jk-minified`) at the product's own
moving version is re-locked against the jk being built, and only its plugin `checksum` rows may
move. Anything else that changes is a real drift and fails the lane.
