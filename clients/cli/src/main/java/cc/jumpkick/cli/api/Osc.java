// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.cli.theme.StreamCaps;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.terminal.Ansi;

/**
 * OSC policy gate. The leaf always constructs the sequence; this type returns empty when {@code
 * --no-osc} or {@code --no-ansi} is set, or stdout may not carry ANSI ({@link StreamCaps}). The
 * terminal-only sequences (taskbar progress, window title, notification) are written only by a live
 * region or a caller that asked {@link StreamCaps#live} for its stream.
 */
public final class Osc {
    private Osc() {}

    public static boolean oscEnabled() {
        return !SessionContext.current().config().noOscOr(false) && StreamCaps.ansi(StreamCaps.Side.OUT);
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

    /**
     * {@code visible} as an OSC 8 hyperlink to {@code url} on stdout, or {@code visible} alone when
     * stdout may not carry one ({@link StreamCaps#links}). Every hyperlink jk prints goes through here.
     */
    public static String hyperlink(String url, String visible) {
        return StreamCaps.links(StreamCaps.Side.OUT) ? Ansi.hyperlink(url, visible) : visible;
    }
}
