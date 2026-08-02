# Netty cold / warm benchmark methodology (JK-1175)

## Scope today

| Deliverable | Status |
|-------------|--------|
| **netty-echo** sample (`jk-examples/jvm/netty-echo`) | JumpKick arm for cold/warm/no-op (published Netty jars) |
| Full multi-module Netty **source** port (~50 Maven modules) | Not in-tree; Mill’s port is the reference graph |
| Three-way Maven vs Mill vs jk table | Run locally with script below; check results into this doc when measured |

## Fairness notes

| Tool | Cold wipe | Parallelism |
|------|-----------|-------------|
| JumpKick | project `target/` only (CAS stays warm unless noted) | `jk -j N` (default auto) |
| Maven | `target/` / `mvn clean`; decide whether `~/.m2` is warm | `-T N` |
| Mill | `out/` clean; decide local ivy/coursier cache | Mill workers |

Document JDK (`java -version`), OS, CPU, and tool versions on every results row.

## JumpKick arm (automated)

```bash
# from jk checkout
./scripts/netty-echo-bench.sh /path/to/jk-examples/jvm/netty-echo
```

Scenarios: cold full `jk build --skip-tests`, warm no-op, single-file dirty.

## Full Netty monorepo (future)

1. Pin Netty SHA (align with Mill `example/thirdparty/netty` if possible).
2. Overlay workspace under `jk-examples/jvm/netty/` (NiA pattern).
3. Parity matrix: Groovy codegen on `common`, JNI natives, shade/OSGi → deferred or scripted.
4. Extend the script with Maven/Mill commands on the same checkout.

## Results (fill in)

| Date | Machine | Tool | Cold ms | Warm no-op ms | Dirty ms | Notes |
|------|---------|------|--------:|--------------:|---------:|-------|
| | | jk (netty-echo) | | | | |
| | | Maven | | | | |
| | | Mill | | | | |
