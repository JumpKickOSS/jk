// SPDX-License-Identifier: Apache-2.0
/**
 * {@code jk-guards-baseline.toml}: the tolerated violations, engine-owned like the lockfile. The
 * engine tightens it on every run; only {@code jk guard freeze <id> --reason} grows it; under CI
 * nothing is written and a loose baseline is red. Fingerprints are line-independent so a reformat
 * never creates a "new" violation.
 */
@NullMarked
package cc.jumpkick.guard.baseline;

import org.jspecify.annotations.NullMarked;
