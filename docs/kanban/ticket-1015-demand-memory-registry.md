# ticket-1015 — Concurrent worker memory demand registry

**Priority:** P3 (deferred with revisit triggers)  
**Status:** open / placeholder  
**Refs:** [architecture.md](../architecture.md) “precise concurrent memory accounting”, [architecture.md](../architecture.md) §5E

## Intent

Only build a live demand registry if instrumentation shows single-build regression or
queueing-while-idle under concurrent load. Until then, coarse shared plan stays.

## Revisit when

- `activeConnections` distribution measured on real use
- Single-build wall-clock regression vs pre-engine sizing
- Synthetic 2/4/8 concurrent builds queue while CPU/RAM idle

## Placeholder

No implementation until a revisit trigger is measured and recorded here.
