// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import cc.jumpkick.cli.Jk;

/**
 * In-process CLI entry point for tests. Static-import {@link #run} so command tests read as
 * {@code run("lock", "-C", ...)} — one shared definition instead of the private copy every test
 * class used to carry.
 */
public final class JkRun {
    private JkRun() {}

    public static int run(String... args) {
        return Jk.execute(args);
    }
}
