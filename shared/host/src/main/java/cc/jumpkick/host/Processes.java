// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import cc.jumpkick.host.time.Clock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Runs a child and captures its output under a deadline that always holds.
 *
 * <p>Both pipes drain on platform daemon threads while the caller waits on the child, so the
 * deadline holds for a child that neither exits nor closes its pipe, and a child whose output
 * outgrows a pipe buffer never blocks on it. Reading a pipe to its end and only then waiting with a
 * timeout does neither: the read blocks until every holder of the pipe closes it. A child past its
 * deadline is killed with its descendants.
 */
public final class Processes {

    /**
     * What a capture saw. {@code exit} is the child's exit code, or {@code -1} when it was killed at
     * the deadline. {@code output} is its stdout (with stderr when the builder merged it) and
     * {@code error} its stderr when it was a pipe of its own, both UTF-8 and as far as they were read.
     */
    public record Captured(int exit, String output, String error, boolean timedOut) {

        /** True when the child exited 0 within the deadline. */
        public boolean ok() {
            return !timedOut && exit == 0;
        }
    }

    private Processes() {}

    /**
     * Run {@code command} with stderr merged into stdout and stdin closed.
     *
     * @throws IOException when the command cannot start, or the caller is interrupted (the child is
     *     killed and the interrupt flag kept)
     */
    public static Captured capture(List<String> command, Duration timeout) throws IOException {
        return capture(new ProcessBuilder(command).redirectErrorStream(true), timeout);
    }

    /** As {@link #capture(List, Duration)} for a builder the caller configured (directory, environment, redirects). */
    public static Captured capture(ProcessBuilder builder, Duration timeout) throws IOException {
        return capture(builder.start(), null, timeout, Integer.MAX_VALUE);
    }

    /** As {@link #capture(List, Duration)} for a child the caller started through its own launcher. */
    public static Captured capture(Process child, Duration timeout) throws IOException {
        return capture(child, null, timeout, Integer.MAX_VALUE);
    }

    /**
     * Capture {@code child}: write {@code stdin} to it (nothing when null) and close its input, then
     * wait at most {@code timeout}. The first {@code limit} bytes of each pipe are kept and the rest
     * is read and dropped, so a child that prints without end neither fills memory nor blocks.
     */
    public static Captured capture(Process child, byte @Nullable [] stdin, Duration timeout, int limit)
            throws IOException {
        Drain out = Drain.start(child.getInputStream(), limit, "jk-process-stdout");
        Drain err = Drain.start(child.getErrorStream(), limit, "jk-process-stderr");
        try (OutputStream in = child.getOutputStream()) {
            if (stdin != null) in.write(stdin);
        } catch (IOException closedEarly) {
            // The child exited or closed its input without reading it; its exit status says why.
        }
        long deadline = Clock.SYSTEM.nanos() + timeout.toNanos();
        try {
            if (!child.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                killTree(child);
                return new Captured(-1, out.text(), err.text(), true);
            }
            // The child is gone, but a descendant may still hold a pipe: wait for the end of the
            // output no later than the deadline, and keep what was read by then.
            out.await(deadline);
            err.await(deadline);
            return new Captured(child.exitValue(), out.text(), err.text(), false);
        } catch (InterruptedException e) {
            killTree(child);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted waiting for pid " + child.pid());
        }
    }

    /**
     * {@code command}'s output (stderr merged) when it exits 0 within {@code timeout}; {@code null}
     * when it cannot start, exits non-zero, times out or the caller is interrupted. For a probe
     * whose every failure means "no answer".
     */
    public static @Nullable String outputIfOk(List<String> command, Duration timeout) {
        return outputIfOk(new ProcessBuilder(command).redirectErrorStream(true), timeout);
    }

    /** As {@link #outputIfOk(List, Duration)} for a builder the caller configured. */
    public static @Nullable String outputIfOk(ProcessBuilder builder, Duration timeout) {
        try {
            Captured c = capture(builder, timeout);
            return c.ok() ? c.output() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Kill {@code child} and every descendant it started. */
    public static void killTree(Process child) {
        child.descendants().forEach(ProcessHandle::destroyForcibly);
        child.destroyForcibly();
    }

    /** Reads one pipe to its end on a platform daemon thread, keeping the first {@code limit} bytes. */
    private static final class Drain implements Runnable {
        private final InputStream in;
        private final int limit;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final Thread thread;

        private Drain(InputStream in, int limit, String name) {
            this.in = in;
            this.limit = limit;
            // A platform thread: a pipe read blocks the thread making it, and on a virtual thread
            // that holds its carrier for as long as the child is silent.
            this.thread = new Thread(this, name);
            thread.setDaemon(true);
        }

        static Drain start(InputStream in, int limit, String name) {
            Drain d = new Drain(in, limit, name);
            d.thread.start();
            return d;
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            try (InputStream stream = in) {
                for (int n; (n = stream.read(buf)) != -1; ) {
                    synchronized (kept) {
                        int room = limit - kept.size();
                        if (room > 0) kept.write(buf, 0, Math.min(n, room));
                    }
                }
            } catch (IOException ignored) {
                // The child was killed mid-read or closed its end; what was read stands.
            }
        }

        void await(long deadlineNanos) throws InterruptedException {
            long left = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - Clock.SYSTEM.nanos());
            thread.join(Math.max(1, left));
        }

        String text() {
            synchronized (kept) {
                return kept.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
