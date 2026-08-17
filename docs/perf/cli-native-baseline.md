# Native `jk` client size baseline (JK-2137)

Recorded so later slim-CLI tickets ([[JK-2138]]–[[JK-2149]]) can claim a real
delta. This is **not** a promise that PRs 1–3 shrink the binary.

## Image

| Field | Value |
|---|---|
| Path | `build/dist/jk` |
| Size | **33 MiB** (`-rwxr-xr-x`, 34603008 bytes class) |
| Built | 2026-08-17 15:31 |
| SHA-256 | `27c28fb56eb2c96a00eaabb5d7eb345f52130d4cd9dfefa083f8f983a37e5df3` |
| Tree SHA | `730bb7e9` (`main` at the JK-2136 branch point) |

`strings build/dist/jk | grep -c <pattern>`:

| Pattern | Count | Notes |
|---|---:|---|
| `ComparableVersion` | 16 | `maven-artifact` — `Versions` only |
| `PluginDescriptor` | 613 | `:core` plugin-manifest parser |
| `cc/jumpkick/plugin/manifest` | 640 | Graal include + baked `*.jk-plugin.toml` / scaffolds |
| `org/tomlj` | 66 | approved CLI TOML job |
| `org/antlr` | 24 | tomlj runtime (partial) |
| `org/codehaus/plexus` | **0** | already tree-shaken |
| `PluginConfig` | 28 | field on `JkBuild` |

`checker-qual` has no `strings` hits (annotation jar).

## Expected deltas

| Tickets | Expect |
|---|---|
| JK-2138 / 2139 / 2140 (jars off classpath) | size-neutral or tens of KB |
| JK-2141 / 2142 / 2149 (stop `JkBuildParser` + drop Graal include) | measurable; hundreds of KB to low single-digit MB |

Do **not** fail those tickets if 2138–2140 do not move `ls -lh`.
