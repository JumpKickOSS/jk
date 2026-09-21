// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.util.FileLocks;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The cross-process half of build exclusivity: one build-like job per checkout, whichever engine
 * runs it. {@link InFlightBuilds} keeps one engine's jobs apart; a draining predecessor beside its
 * successor, or two homes sharing a cache on one machine, need the OS to arbitrate. The lock is
 * {@code target/.jk/build.lock} under the checkout, held for the job's lifetime and released by
 * the OS if the engine dies. The file describes its holder so the refused side can name the
 * running build.
 */
public final class BuildSlot implements Closeable {

    static final String LOCK_FILE = "build.lock";

    private final FileLocks.Hold hold;

    private BuildSlot(FileLocks.Hold hold) {
        this.hold = hold;
    }

    /** Where {@code checkout}'s slot lock lives. */
    static Path lockFile(Path checkout) {
        return checkout.resolve(BuildLayout.TARGET).resolve(".jk").resolve(LOCK_FILE);
    }

    /**
     * Take {@code checkout}'s slot without waiting. Empty when another process holds it, or when
     * the tree refuses a lock file (a read-only checkout builds without the cross-process guard).
     */
    static Optional<BuildSlot> tryTake(Path checkout) {
        try {
            return FileLocks.tryHold(lockFile(checkout)).map(BuildSlot::new);
        } catch (IOException noLock) {
            return Optional.empty();
        }
    }

    /** True when a lock file exists and another process holds it. */
    static boolean heldElsewhere(Path checkout) {
        try {
            Optional<FileLocks.Hold> probe = FileLocks.tryHold(lockFile(checkout));
            if (probe.isEmpty()) return true;
            probe.get().close();
            return false;
        } catch (IOException noLock) {
            return false;
        }
    }

    /** Record who holds the slot, for the engine that is refused. */
    void describe(InFlightBuilds.Hold holder) {
        StringBuilder sb = new StringBuilder();
        sb.append("pid=").append(ProcessHandle.current().pid()).append('\n');
        sb.append("build=").append(holder.buildNumber()).append('\n');
        sb.append("kind=").append(holder.kind()).append('\n');
        sb.append("started=").append(holder.startedAt()).append('\n');
        try {
            hold.write(sb.toString());
        } catch (IOException ignored) {
            // the description is a courtesy to the refused side; the lock itself is what matters
        }
    }

    /**
     * The holder another process described in {@code checkout}'s lock file, as the hold a
     * rejection reports: request id 0 (not this engine's), the build number and kind it wrote.
     */
    static InFlightBuilds.Hold holderOf(Path checkout, String fingerprint, String dir, @Nullable String coord) {
        long build = 0;
        String kind = "build";
        long started = 0;
        for (String line : FileLocks.describeHolder(lockFile(checkout)).split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            switch (k) {
                case "build" -> build = parseLong(v);
                case "kind" -> kind = v.isEmpty() ? kind : v;
                case "started" -> started = parseLong(v);
                default -> {}
            }
        }
        return new InFlightBuilds.Hold(0L, build, fingerprint, kind, dir, coord, started, null, null, null);
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    @Override
    public void close() {
        try {
            hold.close();
        } catch (IOException ignored) {
            // releasing a lock whose channel is already gone is not a failure of the job
        }
    }
}
