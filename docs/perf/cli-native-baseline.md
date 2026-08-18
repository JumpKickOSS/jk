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

## After JK-2149 (2026-08-17 19:50)

| Field | Value |
|---|---|
| Path | `build/dist/jk` |
| Size | **32 MiB** (`-rwxr-xr-x`, 33522176 bytes) |
| Built | 2026-08-17 19:50 |
| SHA-256 | `3c743e056b8db9c91135caac1f5d1227d4a10125efcec2775e1c115520fb36d1` |
| Tree SHA | `30eca0fe` (`JK-2136-slim-native-cli` + empty-builtin CLI fallback) |
| Delta vs JK-2137 | **−1 080 832 bytes** (−1.03 MiB) |

`strings build/dist/jk | grep -c <pattern>`:

| Pattern | Before | After | Notes |
|---|---:|---:|---|
| `ComparableVersion` | 16 | **0** | vendored as `MavenVersion` (JK-2140) |
| `PluginDescriptor` | 613 | 58 | `:core` still on the CLI compile set; types remain reachable |
| `cc/jumpkick/plugin/manifest` | 640 | **2** | Graal include gone; no baked `*.jk-plugin.toml` |
| `spring-boot.jk-plugin.toml` | — | 1 | string in `PluginTableRegistry.BUILT_IN`, not a resource |
| `scaffold/Application.java.tmpl` | — | **0** | |
| `org/tomlj` | 66 | 14 | still the approved CLI TOML job |

Manifests now live on `:engine` + worker jars only (`:core` jar rejects them). Native
CLI `PluginTableRegistry` loads an empty built-in map so leftover lock-freshness
parses do not crash. Splitting `:core` so `PluginDescriptor` leaves the image is
follow-up, not this epic.

## After JK-2151 (2026-08-17 20:45)

Reachability, not a new Gradle module: `ProjectIdentity.coordOf`, `ModuleLayout`,
`NativePreflight`, and `jk tree` styling no longer call `JkBuildParser`. The CLI
asks the engine for lock staleness (`projectInfo.lockStale`) and conservative
freshen uses `LockFreshness.needsRefresh` so a missing lock still writes one.

| Field | Value |
|---|---|
| Path | `build/dist/jk` |
| Size | **32 MiB** (`-rwxr-xr-x`, 33141552 bytes) |
| Built | 2026-08-17 20:45 |
| SHA-256 | `83040bace26cfc419d3133d28ce4701cde08e87e294da6fe8d0e9051a1133532` |
| Tree SHA | `JK-2151-core-split` |
| Delta vs JK-2149 | **−380 624 bytes** (−0.36 MiB) |
| `core.jar` in image | 293.50 kB (was 451 kB at JK-2149) |

`strings build/dist/jk | grep -c <pattern>`:

| Pattern | JK-2149 | JK-2151 | Notes |
|---|---:|---:|---|
| `PluginDescriptor` | 58 | **0** | parser types no longer reachable from CLI |
| `spring-boot.jk-plugin.toml` | 1 | **0** | `PluginTableRegistry.BUILT_IN` left the image |
| `JkBuildParser` | 6 | **0** | |
| `cc/jumpkick/plugin/manifest` | 2 | **0** | |
| `org/tomlj` | 14 | 14 | still the approved CLI TOML job |

## Post-epic measurement (2026-08-18, JK-2165)

After JK-2138–JK-2151 landed plus the dead cache/store walkers were deleted (JK-2165):

| Field | Value |
|---|---|
| Size | **29.8 MiB** (31263816 bytes) — **−3.2 MiB vs baseline** |
| `PluginDescriptor` strings | 0 (was 613) |
| `cc/jumpkick/plugin/manifest` strings | 0 (was 640) |
| `ComparableVersion` strings | 0 (was 16) |
| `cacheUsageStats`/`storeUsageStats` strings | 0 |
