// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one plan-time decision the zero-cost invariant rests on: does this workspace use guards at
 * all? Computed once per build and carried on the plan; when it is {@code false} nothing else in
 * this module is touched — no lane task, no facts pass, no file written, no section rendered.
 *
 * <p>Enabled when any of three things is true: the root {@code jk-guards.toml} exists (one {@code
 * Files.exists}, no read), a {@code src/guard} source set was seen by the suite discovery the
 * planner already performs, or the manifest declares a {@code [guards]} table (already parsed with
 * the manifest). No filesystem call is made here beyond the single stat.
 */
public final class GuardsPresence {

    /** The root rule file. Not under {@code .jk/}: rules are reviewed data, not build logic. */
    public static final String RULES_FILE = "jk-guards.toml";

    /** Under the build output: the digest of the root rules file the last build loaded, for the other build's parity check. */
    public static final String RULES_HASH_FILE = "jk-guards.sha256";

    /** The engine-owned baseline beside it. */
    public static final String BASELINE_FILE = "jk-guards-baseline.toml";

    private GuardsPresence() {}

    public static Path rulesFile(Path root) {
        return root.resolve(RULES_FILE);
    }

    public static Path baselineFile(Path root) {
        return root.resolve(BASELINE_FILE);
    }

    /**
     * @param root the workspace root (the invocation root's manifest directory)
     * @param guardSuiteSeen whether suite discovery found a {@code src/guard} tree anywhere
     * @param manifestDeclaresGuards whether the parsed root manifest has a {@code [guards]} table
     */
    public static boolean detect(Path root, boolean guardSuiteSeen, boolean manifestDeclaresGuards) {
        if (guardSuiteSeen || manifestDeclaresGuards) return true;
        return Files.exists(rulesFile(root));
    }

    /** The manifest the {@code [guards]} table is read from. */
    public static Path manifest(Path root) {
        return root.resolve(ManifestPaths.MANIFEST);
    }
}
