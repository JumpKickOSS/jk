// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.terminal.Ansi;

/**
 * OSC policy gate. The leaf always constructs the sequence; this type returns empty when {@code
 * --no-osc} is set.
 */
public final class Osc {
    private Osc() {}

    public static boolean oscEnabled() {
        return !SessionContext.current().config().noOscOr(false);
    }

    public static String taskbarProgress(int percent) {
        return oscEnabled() ? Ansi.taskbarProgress(percent) : "";
    }

    public static String taskbarIndeterminate() {
        return oscEnabled() ? Ansi.taskbarIndeterminate() : "";
    }

    public static String taskbarClear() {
        return oscEnabled() ? Ansi.taskbarClear() : "";
    }

    public static String windowTitle(String title) {
        return oscEnabled() ? Ansi.windowTitle(title) : "";
    }

    public static String windowTitleClear() {
        return oscEnabled() ? Ansi.windowTitleClear() : "";
    }

    public static String desktopNotify(String title, String body) {
        return oscEnabled() ? Ansi.desktopNotify(title, body) : "";
    }
}
