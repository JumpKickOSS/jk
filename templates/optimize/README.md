# Optimize training fixtures

Tiny projects used by `jk optimize` and language-aware host calibration.

**Not for end users.** Pins latest shipping languages (Kotlin 2.4.10, Groovy 5.0.8);
older versions train on-demand during real builds.
Not end-user templates.

| Fixture | Sources | Tests |
|---------|---------|-------|
| `java-train` | 10 | 2 classes × 5 methods |
| `kotlin-train` | 10 | 2 × 5 |
| `groovy-train` | 10 | 2 × 5 |

Materialized to a temp directory by optimize; deleted after use.
