// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.terminal.Width;
import org.jspecify.annotations.Nullable;

/** Shared ANSI strip for CLI testsprefer this over private per-class copies. */
public final class TestAnsi {
    private TestAnsi() {}

    public static String strip(@Nullable String s) {
        return s == null ? "" : Width.stripAnsi(s);
    }
}
