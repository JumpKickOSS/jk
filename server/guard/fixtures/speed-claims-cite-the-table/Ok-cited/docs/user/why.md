# Why

<!-- wall-table:begin -->
| Scenario | jk median / p90 | Gradle median / p90 |
|---|---:|---:|
| No-op | 0.19 s / 0.20 s | 0.52 s / 0.54 s |

| Scenario | jk peak RSS | Gradle peak RSS |
|---|---:|---:|
| No-op | 313 MiB | 1,363 MiB |
<!-- wall-table:end -->

| [Measured](performance.md) | Against Gradle |
|---|---|
| Warm rebuild | Faster (0.21 s vs 0.60 s) |
