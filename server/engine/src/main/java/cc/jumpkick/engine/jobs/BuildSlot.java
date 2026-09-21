// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.builds.CheckoutSlot;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.util.FileLocks;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
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

    private final FileLocks.Hold hold;

    private BuildSlot(FileLocks.Hold hold) {
        this.hold = hold;
    }

    /** Where {@code checkout}'s slot lock lives. */
    static Path lockFile(Path checkout) {
        return CheckoutSlot.lockFile(checkout);
    }

    /** What {@link #take} found. */
    sealed interface Outcome permits Taken, Held, Unguarded {}

    /** This engine holds the checkout; the slot closes with the job. */
    record Taken(BuildSlot slot) implements Outcome {}

    /** Another engine — or another job in this one — holds the checkout. */
    record Held() implements Outcome {}

    /** The tree refuses a lock file, or the filesystem refuses locks; the build runs without the guard. */
    record Unguarded(String reason) implements Outcome {}

    /**
     * Take {@code checkout}'s slot without waiting. The file names this engine at once so a
     * refusal in the moments before the build number is known still finds a holder.
     */
    static Outcome take(Path checkout) {
        return switch (FileLocks.tryHold(lockFile(checkout))) {
            case FileLocks.Hold hold -> {
                BuildSlot slot = new BuildSlot(hold);
                slot.write("pid=" + ProcessHandle.current().pid() + "\n");
                yield new Taken(slot);
            }
            case FileLocks.Held held -> new Held();
            case FileLocks.Unavailable unavailable -> new Unguarded(unavailable.reason());
        };
    }

    /** Record who holds the slot, for the engine that is refused. */
    void describe(InFlightBuilds.Hold holder) {
        StringBuilder sb = new StringBuilder();
        sb.append("pid=").append(ProcessHandle.current().pid()).append('\n');
        sb.append("build=").append(holder.buildNumber()).append('\n');
        sb.append("kind=").append(holder.kind()).append('\n');
        sb.append("started=").append(holder.startedAt()).append('\n');
        write(sb.toString());
    }

    private void write(String description) {
        try {
            hold.write(description);
        } catch (IOException ignored) {
            // the description is a courtesy to the refused side; the lock itself is what matters
        }
    }

    /**
     * The holder another process described in {@code checkout}'s lock file, as the hold a
     * rejection reports: request id 0 (not this engine's), the build number and kind it wrote. A
     * holder that has not written its build number yet reports number 0.
     */
    static InFlightBuilds.Hold holderOf(Path checkout, String fingerprint, String dir, @Nullable String coord) {
        CheckoutSlot.Holder h = CheckoutSlot.holderOf(checkout);
        return new InFlightBuilds.Hold(
                0L, h.buildNumber(), fingerprint, h.kind(), dir, coord, h.startedAt(), null, null, null);
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
