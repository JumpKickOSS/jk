// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.scaffold.NewInputs;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** CLI test facade over {@link cc.jumpkick.scaffold.NewScaffolder}. */
public final class NewScaffolder {

    /** @see cc.jumpkick.scaffold.NewScaffolder#CURATED_DEPS */
    public static final Map<String, List<cc.jumpkick.scaffold.NewScaffolder.CuratedEntry>> CURATED_DEPS =
            cc.jumpkick.scaffold.NewScaffolder.CURATED_DEPS;

    public record CuratedEntry(String coord, String version, String scope) {}

    private NewScaffolder() {}

    public static void write(NewInputs inputs) throws IOException {
        write(inputs, true);
    }

    public static void write(NewInputs inputs, boolean standalone) throws IOException {
        cc.jumpkick.scaffold.NewScaffolder.write(inputs, standalone);
    }
}
