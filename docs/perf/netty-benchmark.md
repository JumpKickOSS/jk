# Netty cold / warm benchmark methodology (JK-1175)

## Scope today

| Deliverable | Status |
|-------------|--------|
| **netty-echo** sample ([jk-examples](https://github.com/jkbuild/jk-examples)/`jvm/netty-echo`) | JumpKick arm for cold/warm/no-op (published Netty jars) |
| Full multi-module Netty **source** port (~40 workspace modules) | **In [jk-examples/jvm/netty](https://github.com/jkbuild/jk-examples)** — Mill graph parity; `jk build --skip-tests` green |
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

## Full Netty monorepo (jk-examples)

Port: [jkbuild/jk-examples](https://github.com/jkbuild/jk-examples) `jvm/netty/` (tag `netty-4.1.115.Final`, Mill module graph).

```bash
cd jk-examples/jvm/netty && ./setup.sh && ./run.sh
./scripts/bench-netty.sh   # cold / warm / dirty
```

See that tree’s `PARITY.md` for JNI / compiler-args gaps.

## Full Netty monorepo (remaining)


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
