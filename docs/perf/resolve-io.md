# Resolve / lock I/O (JK-1088)

## What costs time on first lock

Cold `jk lock` for large graphs (Spring Boot ~90 packages) is dominated by:

1. **maven-metadata.xml** per GA (disk TTL 24h + conditional GET when warm)
2. **POM + parent chain** fetches per package (online path re-HTTP every time until local-first)
3. **Three scope solves** (main / test / processor) with shared in-memory caches (post-1088)
4. **Jar download + CAS + upstream checksum** per package (`toArtifact`)

BOM pins **prefer** versions; they do not skip metadata/POM network work by themselves.

## Optimizations landed

| Change | Effect |
|--------|--------|
| Local-first online fetch from `repos/<name>/` | Warm re-lock skips re-HTTP for POMs/jars |
| Shared `EffectivePomBuilder` + `MavenPackageSource` across scopes | Avoid re-walking overlapping Spring GAs thrice |
| Graph-phase progress (`onGraphPackage` / `onPhase`) | Bar advances during solve, not only jar fetch |
| Dual-phase tick budget (~2× packages) | Graph + materialize each contribute to the bar |

## Progress bar model

- Initial ticks ≈ `max(10, declaredRoots × 12) × 2`
- During graph: +1 tick per unique package decided; grow total if estimate undershoots
- After graph: set total to graphTicks + packages for materialize
- During materialize: +1 tick per package fetched/recorded

## Related

- JK-1089: platform BOM in tree is pin source, not `(missing)`
- Warm metadata: `MavenMetadataCache` (24h)
