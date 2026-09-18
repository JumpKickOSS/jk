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

    private BoundedRun() {}

    /**
     * Wait up to {@code timeout} for {@code process} (stderr already merged by the fork owner),
     * collecting its output; empty when it ran out of time, in which case it has been destroyed.
     */
    static Optional<Outcome> await(Process process, Duration timeout) throws InterruptedException {
        StringBuffer captured = new StringBuffer();
        Thread reader = Thread.ofPlatform().daemon().start(() -> {
            try (var in = process.inputReader(StandardCharsets.UTF_8)) {
                in.lines().forEach(line -> captured.append(line).append('\n'));
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
        return Optional.of(new Outcome(process.exitValue(), captured.toString()));
    }
}
