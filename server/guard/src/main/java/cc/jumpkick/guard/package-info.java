// SPDX-License-Identifier: Apache-2.0
/**
 * The house-rule guard engine. A rule is a {@code [guards.<id>]} table in {@code jk-guards.toml} or
 * a {@code @Guard} method in a project's {@code src/guard} suite; both read the same facts and share
 * one baseline, one fingerprint scheme and one report. Subpackages: {@code schema} (kinds and
 * keys), {@code facts} (the per-module index the compile task writes), {@code eval} (the
 * evaluators and the honesty semantics every kind inherits), {@code baseline} (the engine-owned
 * ratchet file), {@code report} (markdown, diagnostics, SARIF).
 */
@NullMarked
package cc.jumpkick.guard;

import org.jspecify.annotations.NullMarked;
