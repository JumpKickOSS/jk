// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which published release an environment is given, pinned to the version of the JumpKick that
 * installs it, so the two sides of one machine speak the same protocol. A release installs itself.
 * A {@code -SNAPSHOT} is never published, so it installs the newest published release at or below
 * its base version ({@code 0.51.2-SNAPSHOT} installs {@code 0.51.1}): the patch releases of its own
 * line are asked for from the base down, then the latest pointer is taken if it is not newer.
 */
public final class ReleasePin {

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:[-.][A-Za-z0-9.-]+)?");

    /** Whether a release directory serves {@code version}. */
    @FunctionalInterface
    public interface Published {
        /** True when {@code version} is published; false on a 404; an exception when nobody could say. */
        boolean has(String version) throws IOException;
    }

    /** The release the latest pointer names; the fallback once the base's own line has none. */
    @FunctionalInterface
    public interface Latest {
        String version() throws IOException;
    }

    /** The release to install and the line that says why, for the progress and the change log. */
    public record Pin(String version, String why) {}

    private ReleasePin() {}

    /** The pin for a JumpKick of version {@code host}; null when no published release fits. */
    public static @Nullable Pin of(String host, Published published, Latest latest) throws IOException {
        Matcher m = VERSION.matcher(host.strip());
        if (!m.matches()) return null;
        int major = Integer.parseInt(m.group(1)), minor = Integer.parseInt(m.group(2));
        int patch = Integer.parseInt(m.group(3));
        if (!host.strip().endsWith("-SNAPSHOT")) return new Pin(host.strip(), "the version of JumpKick for Windows");
        for (int p = patch; p >= 0; p--) {
            String candidate = major + "." + minor + "." + p;
            if (published.has(candidate)) return new Pin(candidate, snapshotWhy(host, candidate));
        }
        String newest = latest.version().strip();
        Matcher l = VERSION.matcher(newest);
        if (l.matches() && compare(l, major, minor, patch) <= 0) return new Pin(newest, snapshotWhy(host, newest));
        return null;
    }

    /** {@code 0.51.2-SNAPSHOT} is not published: the newest release at or below it. */
    private static String snapshotWhy(String host, String pinned) {
        return host.strip() + " is a development build, never published: the newest release at or below it, " + pinned;
    }

    private static int compare(Matcher v, int major, int minor, int patch) {
        int c = Integer.compare(Integer.parseInt(v.group(1)), major);
        if (c == 0) c = Integer.compare(Integer.parseInt(v.group(2)), minor);
        if (c == 0) c = Integer.compare(Integer.parseInt(v.group(3)), patch);
        return c;
    }
}
