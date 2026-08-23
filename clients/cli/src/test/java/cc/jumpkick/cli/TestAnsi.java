// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

/** Shared ANSI strip for CLI testsprefer this over private per-class copies. */
public final class TestAnsi {
    private TestAnsi() {}

    public static String strip(String s) {
        return s == null ? null : cc.jumpkick.terminal.Width.stripAnsi(s);
    }
}
