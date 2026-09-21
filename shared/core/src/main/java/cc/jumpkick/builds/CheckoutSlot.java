// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The checkout's build slot file, {@code target/.jk/build.lock}: one build-like job per checkout
 * holds it for the job's lifetime, whichever engine runs it, and describes itself in it. The
 * engine takes the slot; the client only asks who holds it, so {@code jk clean} does not unlink
 * the lock a running build sits on.
 */
public final class CheckoutSlot {

    public static final String LOCK_FILE = "build.lock";

    private CheckoutSlot() {}

    /** Where {@code checkout}'s slot lock lives. */
    public static Path lockFile(Path checkout) {
        return checkout.resolve(BuildLayout.TARGET).resolve(".jk").resolve(LOCK_FILE);
    }

    /** What a holder wrote about itself; {@code buildNumber} is 0 until it is allocated. */
    public record Holder(long pid, long buildNumber, String kind, long startedAt) {}

    /** The holder when another process has {@code checkout}'s slot; empty when it is free or unlockable. */
    public static Optional<Holder> heldBy(Path checkout) {
        switch (FileLocks.tryHold(lockFile(checkout))) {
            case FileLocks.Hold hold -> {
                try {
                    hold.close();
                } catch (IOException ignored) {
                    // a probe's hold; nothing depends on it
                }
                return Optional.empty();
            }
            case FileLocks.Held held -> {
                return Optional.of(holderOf(checkout));
            }
            case FileLocks.Unavailable unavailable -> {
                return Optional.empty();
            }
        }
    }

    /** The description in {@code checkout}'s lock file, zeros and {@code build} where a line is missing. */
    public static Holder holderOf(Path checkout) {
        long pid = 0;
        long build = 0;
        String kind = "build";
        long started = 0;
        for (String line : FileLocks.describeHolder(lockFile(checkout)).split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            switch (k) {
                case "pid" -> pid = parseLong(v);
                case "build" -> build = parseLong(v);
                case "kind" -> kind = v.isEmpty() ? kind : v;
                case "started" -> started = parseLong(v);
                default -> {}
            }
        }
        return new Holder(pid, build, kind, started);
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
