// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.scaffold.NewInputs;
import cc.jumpkick.scaffold.ScaffoldVersions;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * CLI test facade over {@link cc.jumpkick.scaffold.NewScaffolder}: every version a scaffold would
 * look up resolves to {@link #VERSION}, so no test touches a repository.
 */
public final class NewScaffolder {

    /** The number the fake repositories report for every coordinate. */
    public static final String VERSION = "1.2.3";

    /** Every coordinate's newest stable release is {@link #VERSION}. */
    public static final ScaffoldVersions VERSIONS = (group, artifact) -> VERSION;

    /** @see cc.jumpkick.scaffold.NewScaffolder#CURATED_DEPS */
    public static final Map<String, List<cc.jumpkick.scaffold.NewScaffolder.CuratedEntry>> CURATED_DEPS =
            cc.jumpkick.scaffold.NewScaffolder.CURATED_DEPS;

    private NewScaffolder() {}

    public static void write(NewInputs inputs) throws IOException {
        write(inputs, true);
    }

    public static void write(NewInputs inputs, boolean standalone) throws IOException {
        cc.jumpkick.scaffold.NewScaffolder.write(inputs, standalone, VERSIONS);
    }
}
