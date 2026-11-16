// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The {@code JK_HOME} a person chose for good: on Windows the one set as a User environment
 * variable ({@code HKCU\Environment}), which every new process inherits. A home named that way is
 * an install of its own, as {@code %USERPROFILE%\.jk} is (the Dev Drive setup puts it on another
 * volume), so it claims the User PATH, the activation and the desktop identity. A {@code JK_HOME}
 * set for one process or shell alone (a scratch build, a hermetic test, a checkout's own dist) is a
 * private home and claims none of them.
 */
public final class PersistedHome {

    private PersistedHome() {}

    /** How long {@code reg query} may take: it answers in milliseconds. */
    private static final Duration REG_WAIT = Duration.ofSeconds(5);

    /** The User environment's {@code JK_HOME} on Windows; null when unset, unreadable or not on Windows. */
    public static @Nullable String windowsUserJkHome() {
        if (!Os.isWindows()) return null;
        try {
            Processes.Captured c = Processes.capture(
                    List.of("reg", "query", "HKCU\\Environment", "/v", "JK_HOME"), REG_WAIT);
            if (c.timedOut() || c.exit() != 0) return null;
            return regValue(c.output(), "JK_HOME");
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * {@code name}'s value in {@code reg query} output ({@code    JK_HOME    REG_SZ    D:\jk}),
     * with {@code %VAR%} references left as written; null when the output names no such value.
     */
    static @Nullable String regValue(@Nullable String output, String name) {
        if (output == null) return null;
        for (String line : output.split("\\R")) {
            String[] parts = line.strip().split("\\s{2,}|\\t", 3);
            if (parts.length == 3 && parts[0].equalsIgnoreCase(name) && parts[1].startsWith("REG_")) {
                String value = parts[2].strip();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * True when {@code home}, a Windows path, names the persisted {@code persisted} home: compared as
     * Windows compares paths (case folded, either separator, a trailing one ignored). A persisted value
     * that is not an absolute Windows path (a drive or a UNC share) names no home.
     */
    static boolean same(String home, @Nullable String persisted) {
        if (persisted == null) return false;
        String p = key(persisted);
        boolean absolute = p.matches("[a-z]:\\\\.*") || p.startsWith("\\\\");
        return absolute && p.equals(key(home));
    }

    /** True when {@code home} is this user's persisted Windows {@code JK_HOME}. */
    public static boolean isPersisted(Path home) {
        return same(home.toAbsolutePath().normalize().toString(), windowsUserJkHome());
    }

    private static String key(String path) {
        String s = path.strip().replace('/', '\\');
        while (s.length() > 3 && s.endsWith("\\")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }
}
