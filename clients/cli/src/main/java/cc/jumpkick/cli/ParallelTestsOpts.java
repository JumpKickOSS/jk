// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.List;

/**
 * Cross-module test concurrency flags (C2). Default <strong>on</strong> so monorepo suites share the
 * jobs pool for run-tests (Mill/Gradle-shaped). Hermetic modules still pin {@code [test] workers=1};
 * suites that need a full serial gate use {@code --serial-tests}.
 */
public final class ParallelTestsOpts {

    private ParallelTestsOpts() {}

    /** Flag definitions shared by {@code jk build}, {@code jk test}, and {@code jk explain}. */
    public static List<Opt> options() {
        return List.of(
                Opt.flag("Run modules' tests concurrently (cross-module). Default: on.", "--parallel-tests"),
                Opt.flag(
                        "Serialize modules' tests (opt out of default cross-module parallel).",
                        "--serial-tests",
                        "--no-parallel-tests"));
    }

    /**
     * Effective policy: default on; {@code --serial-tests} / {@code --no-parallel-tests} force off.
     * {@code --parallel-tests} is an affirmative no-op when the default is already on.
     */
    public static boolean enabled(Invocation in) {
        if (in.isSet("serial-tests") || in.isSet("no-parallel-tests")) return false;
        return true;
    }
}
