// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.notices;

import cc.jumpkick.config.ReadUsage;
import java.nio.file.Path;

/**
 * The bell's one "jk reads token counts" while {@code [agents] read-usage} is on: raised by the
 * first engine that finds it on and never again on this machine. It links to the console, where
 * each episode's receipt shows the counts.
 */
public final class ReadUsageNotice {

    /** The notice kind. */
    public static final String KIND = "read-usage";

    /** Where it links: the console, whose episodes end with their receipts. */
    public static final String HREF = "#console";

    private ReadUsageNotice() {}

    /** Raise the notice when reading is on in {@code userConfig} and the bell has not said so; true if raised. */
    public static boolean announce(Notices notices, Path userConfig, Path stateDir) {
        if (!ReadUsage.on(userConfig) || !ReadUsage.firstNotice(stateDir)) return false;
        notices.add(KIND, ReadUsage.NOTICE + "; " + ReadUsage.OFF, HREF, null);
        return true;
    }
}
