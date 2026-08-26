// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Path;

/**
 * Locates the CLI module's {@code src/main/java} for the guards that scan source text rather than
 * behaviour (a rule like "only dispatch begins an envelope" has no runtime seam to assert on).
 *
 * <p>This used to return an {@code Optional} so a run that could not find the tree skipped the
 * scan instead of failing it. Nothing in this repo runs these tests from outside the checkout, so
 * the only thing that hatch ever covered was a broken search — three guards silently passing.
 * {@link RepoRoot} throws instead.
 */
public final class MainSources {
    private MainSources() {}

    /** {@code clients/cli/src/main/java}. */
    public static Path locate() {
        return RepoRoot.dir(MainSources.class, "clients/cli/src/main/java");
    }
}
