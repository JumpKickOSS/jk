// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import org.jline.utils.AttributedString;

/** Shared ANSI strip for CLI tests (JK-1131) — prefer this over private per-class copies. */
public final class TestAnsi {
    private TestAnsi() {}

    public static String strip(String s) {
        return s == null ? null : AttributedString.stripAnsi(s);
    }
}
