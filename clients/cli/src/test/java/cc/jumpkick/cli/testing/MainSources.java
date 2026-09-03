// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Path;

/**
 * Locates the CLI module's {@code src/main/java} for the guards that scan source text rather than
 * behaviour (a rule like "only dispatch begins an envelope" has no runtime seam to assert on).
 *
 * <p>{@link RepoRoot} throws if the tree is missing — these tests only run from a checkout.
 */
public final class MainSources {
    private MainSources() {}

    /** {@code clients/cli/src/main/java}. */
    public static Path locate() {
        return RepoRoot.dir(MainSources.class, "clients/cli/src/main/java");
    }
}
