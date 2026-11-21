// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A child process this plugin cannot trust to finish — a foreign {@code java}, a container daemon,
 * Boot's extractor — drained on its own thread while the caller waits out a deadline.
 *
 * <p>Reading the child to EOF on the calling thread puts the deadline out of reach: a wedged child
 * keeps its pipe open, the read never returns, and the wait that was meant to bound the probe never
 * starts. Here the drain runs beside the wait, and a child that outlives the deadline is killed
 * rather than left holding the step.
 */
final class BoundedRun {

    /** What a child that finished in time said, and how it exited. */
    record Outcome(int exit, String output) {}

    /** How much of a child's output is kept, in chars: its last lines, where a failure says why. */
    static final int KEPT_CHARS = 64 * 1024;

    private BoundedRun() {}

    /**
     * Wait up to {@code timeout} for {@code process} (stderr already merged by the fork owner),
     * collecting the last {@link #KEPT_CHARS} of its output; empty when it ran out of time, in which
     * case it has been destroyed. The child is read to the end however much it says.
     */
    static Optional<Outcome> await(Process process, Duration timeout) throws InterruptedException {
        Tail captured = new Tail(KEPT_CHARS);
        Thread reader = Thread.ofPlatform().daemon().start(() -> {
            try (var in = process.inputReader(StandardCharsets.UTF_8)) {
                char[] buffer = new char[8192];
                for (int n; (n = in.read(buffer)) >= 0; ) captured.add(buffer, n);
            } catch (IOException ignored) {
                // The pipe went away with the child; whatever arrived before is kept.
            }
        });
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return Optional.empty();
        }
        reader.join(5_000);
        return Optional.of(new Outcome(process.exitValue(), captured.text()));
    }

    /**
     * The last {@code budget} chars a child wrote. Reads are appended and the front trimmed in
     * batches, so a child that never ends a line is bounded too.
     */
    static final class Tail {

        private final int budget;
        private final StringBuilder kept = new StringBuilder();
        private boolean trimmed;

        Tail(int budget) {
            this.budget = budget;
        }

        synchronized void add(char[] chars, int count) {
            kept.append(chars, 0, count);
            if (kept.length() > 2 * budget) trim();
        }

        /**
         * What was kept. A trimmed tail starts at its first whole line, after a line saying that
         * earlier output is gone.
         */
        synchronized String text() {
            if (kept.length() > budget) trim();
            if (!trimmed) return kept.toString();
            int lineStart = kept.indexOf("\n") + 1;
            return "… earlier output not kept\n" + kept.substring(lineStart);
        }

        private void trim() {
            kept.delete(0, kept.length() - budget);
            trimmed = true;
        }
    }
}
