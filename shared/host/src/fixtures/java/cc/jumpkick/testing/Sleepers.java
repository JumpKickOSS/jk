// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Stand-in processes for tests that need something alive and silent for a while: a worker to
 * kill, an engine to mistake, a trainer that overruns. Built from what the host has — {@code
 * sleep} on POSIX, {@code cmd.exe} with a looping {@code ping} on Windows, where neither {@code
 * sleep} nor {@code sh} exists and a bare {@code bash} may resolve to the WSL launcher.
 */
public final class Sleepers {

    private Sleepers() {}

    /** A process that sleeps {@code seconds} and exits 0. */
    public static ProcessBuilder sleeper(int seconds) {
        return windows()
                ? new ProcessBuilder(cmdExe(), "/c", "ping -n " + (seconds + 1) + " 127.0.0.1 > nul")
                : new ProcessBuilder("sleep", Integer.toString(seconds));
    }

    /** As {@link #sleeper}, with {@code word} on its command line and nothing else about it. */
    public static ProcessBuilder sleeperMentioning(int seconds, String word) {
        return windows()
                ? new ProcessBuilder(cmdExe(), "/c", "ping -n " + (seconds + 1) + " 127.0.0.1 > nul & rem " + word)
                : new ProcessBuilder("sh", "-c", "sleep " + seconds + "; :", word);
    }

    /** A process that writes {@code text} and a newline to the file at {@code out}, then exits 0. */
    public static List<String> writesLine(String text, Path out) {
        return windows()
                ? List.of(cmdExe(), "/c", "echo " + text + "> \"" + out + "\"")
                : List.of("/bin/sh", "-c", "echo " + text + " > '" + out + "'");
    }

    /** A process that exits {@code code} at once, writing nothing. */
    public static List<String> exits(int code) {
        return windows() ? List.of(cmdExe(), "/c", "exit " + code) : List.of("/bin/sh", "-c", "exit " + code);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static String cmdExe() {
        String root = System.getenv("SystemRoot");
        return Path.of(root == null ? "C:\\Windows" : root, "System32", "cmd.exe")
                .toString();
    }
}
