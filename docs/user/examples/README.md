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
| [third-party-plugin](third-party-plugin/) | A build plugin compiled against the published `cc.jumpkick:jk-plugin-sdk` coordinate |
| [openapi-spring](openapi-spring/) | `[openapi]` generating a Spring interface from a contract, implemented by a Boot controller |

Each sample commits its `jk-lock.toml` (`vite-sidecar` its `web/package-lock.json` too), and the nightly builds every sample and fails if a
build rewrote its lock — lockfile-as-law, demonstrated. A sample that uses a first-party plugin
(`minified-cli` → `cc.jumpkick:jk-minified`) pins it by version alone while jk is pre-1.0, so a
plugin rebuild at the same version leaves the committed lock byte-identical; see
[the lockfile guide](../lockfile.md#what-else-the-lock-pins). Any lock line a sample build
changes is a real drift and fails the lane. `third-party-plugin` commits no lock until the SDK
release it depends on is served from `jumpkick.build/repo`; the nightly skips a lock-less sample
and `ThirdPartyPluginExampleTest` builds it against a local repository instead.
