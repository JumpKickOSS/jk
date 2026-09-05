// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.engine.JobWorkers;
import cc.jumpkick.jsonl.BoundedLineReader;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Parent-side child-JVM plugin driver: fork, split protocol lines from chatter, wait for exit.
 * {@link #run} is fire-and-read; {@link #converse} is two-way over stdin.
 */
public final class PluginProcess {

    private PluginProcess() {}

    /** A handle for talking back to a running plugin over its stdin. */
    public interface Conversation {
        /** Send one line to the plugin's stdin (a newline is appended and flushed). */
        void send(String line);

        /** Close the plugin's stdin, signalling end-of-input (EOF). */
        void closeInput();
    }

    /**
     * Fork {@code command}, stream its output, and return the process exit code. The parent only
     * reads — there is no stdin interaction.
     *
     * @param command full process command line (java exe, classpath/jar, main, args)
     * @param prefix marker identifying protocol lines (e.g. {@code "##JKGIT:"})
     * @param onProtocol receives each protocol line with the prefix stripped
     * @param onPassthrough receives each non-protocol line verbatim; may be {@code null} to drop them
     */
    public static int run(
            List<String> command, String prefix, Consumer<String> onProtocol, @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return run(command, Map.of(), prefix, onProtocol, onPassthrough);
    }

    /**
     * As {@link #run(List, String, Consumer, Consumer)}, adding {@code extraEnv} to the child's
     * environment.
     */
    public static int run(
            List<String> command,
            Map<String, String> extraEnv,
            String prefix,
            Consumer<String> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return run(command, extraEnv, null, prefix, onProtocol, onPassthrough);
    }

    /**
     * One-shot (parent only reads): forks, <strong>closes the child's stdin immediately</strong>, then
     * streams stdout. Closing stdin matters — an open pipe with no writer makes {@code System.in}
     * {@code readLine()} hang forever, which is exactly how a suite that prompts for confirmation
     * (e.g. {@code jk self nuke} without {@code -y}) deadlocks under {@code jk test}/{@code jk
     * build}.
     */
    public static int run(
            List<String> command,
            Map<String, String> extraEnv,
            @Nullable Path workDir,
            String prefix,
            Consumer<String> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(
                command,
                extraEnv,
                workDir,
                prefix,
                (json, convo) -> onProtocol.accept(json),
                onPassthrough,
                /* closeStdinImmediately */ true);
    }

    /**
     * Fork {@code command} and drive a two-way conversation: each protocol line is delivered to
     * {@code onProtocol} along with a {@link Conversation} for sending commands back to the plugin's
     * stdin.
     *
     * <p>Reading stdout and writing stdin both happen on the calling thread, so the plugin must
     * alternate (await input → emit → await input) rather than flood stdout while blocked on stdin
     * which the pull protocol does (it emits {@code ready}, then waits for the next command). Run one
     * {@code converse} per worker on its own thread for parallel pull queues.
     *
     * @param onPassthrough receives each non-protocol line; may be {@code null} to drop
     */
    public static int converse(
            List<String> command,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(command, Map.of(), prefix, onProtocol, onPassthrough);
    }

    /**
     * As {@link #converse(List, String, BiConsumer, Consumer)}, adding {@code extraEnv} to the
     * child's environment.
     */
    public static int converse(
            List<String> command,
            Map<String, String> extraEnv,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(command, extraEnv, null, prefix, onProtocol, onPassthrough);
    }

    /**
     * As {@link #converse(List, Map, String, BiConsumer, Consumer)} with an optional working
     * directory (Quarkus {@code @QuarkusTest} resolves the project from the process cwd).
     */
    public static int converse(
            List<String> command,
            Map<String, String> extraEnv,
            @Nullable Path workDir,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(command, extraEnv, workDir, prefix, onProtocol, onPassthrough, false);
    }

    private static int converse(
            List<String> command,
            Map<String, String> extraEnv,
            @Nullable Path workDir,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough,
            boolean closeStdinImmediately)
            throws IOException, InterruptedException {
        return converse(command, extraEnv, workDir, prefix, onProtocol, onPassthrough, closeStdinImmediately, 0L);
    }

    /**
     * As {@link #converse(List, String, BiConsumer, Consumer)} but WITHOUT taking a process-lifetime
     * worker slot. The caller — the long-lived Zinc pull session — meters {@link PluginSlots} itself,
     * once per in-flight COMPILE/PLAN exchange, so an idle resident worker does not pin a permit for
     * the whole job and deadlock nested forks such as the test runner.
     */
    public static int converseNoSlot(
            List<String> command,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        return converse(pb, prefix, onProtocol, onPassthrough, false, 0L);
    }

    /**
     * As {@link #converse(List, Map, Path, String, BiConsumer, Consumer)} with an inactivity
     * watchdog: when the child emits no output line for {@code idleTimeoutMs}, it is
     * force-killed (process tree) and the conversation ends with its (non-zero) exit code.
     * {@code 0} = no watchdog — compiler workers are legitimately silent for long stretches;
     * only callers whose protocol guarantees a heartbeat-ish cadence (the test runner's
     * per-test events) should pass a window.
     */
    public static int converse(
            List<String> command,
            Map<String, String> extraEnv,
            @Nullable Path workDir,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough,
            boolean closeStdinImmediately,
            long idleTimeoutMs)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (extraEnv != null && !extraEnv.isEmpty()) pb.environment().putAll(extraEnv);
        if (workDir != null && Files.isDirectory(workDir)) pb.directory(workDir.toFile());
        // Hold a worker slot for the child's whole lifetime so no more than the
        // memory plan's parallelism run at once (open gate when unconfigured).
        try (PluginSlots.Lease lease = PluginSlots.acquire()) {
            return converse(pb, prefix, onProtocol, onPassthrough, closeStdinImmediately, idleTimeoutMs);
        }
    }

    private static int converse(
            ProcessBuilder pb,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough,
            boolean closeStdinImmediately,
            long idleTimeoutMs)
            throws IOException, InterruptedException {
        // Every worker forks through here, so this is where it loses the engine's terminal.
        pb.command(WorkerSession.detached(pb.command()));
        Process process = JobWorkers.start(pb);
        final AtomicLong lastLineAt = new AtomicLong(System.currentTimeMillis());
        Thread watchdog = null;
        if (idleTimeoutMs > 0) {
            watchdog = Thread.ofVirtual().name("jk-worker-watchdog").unstarted(() -> {
                while (process.isAlive() || hasLiveDescendant(process)) {
                    long idle = System.currentTimeMillis() - lastLineAt.get();
                    if (idle >= idleTimeoutMs) {
                        forceStop(process);
                        return;
                    }
                    try {
                        Thread.sleep(Math.min(idleTimeoutMs - idle + 50, 5_000));
                    } catch (InterruptedException e) {
                        return; // conversation finished normally
                    }
                }
            });
            watchdog.start();
        }
        // Bounded like the client socket: a worker emitting an unbounded line must not OOM the
        // engine. No idle timeout — a compiling worker is legitimately silent for long stretches.
        //
        // The pump runs on its own virtual thread so this (job) thread can give up on the pipe:
        // on Linux a root that exits leaving a reparented child holding the stdout write end
        // produces neither EOF nor a visible descendant — descendants() of a dead process is
        // empty, and closing the fd does not wake a blocked native pipe read. The job thread
        // waits root-exit + a drain grace, then abandons the reader instead of hanging forever.
        BufferedReader reader =
                new BoundedLineReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        AtomicBoolean abandoned = new AtomicBoolean();
        AtomicReference<IOException> pumpError = new AtomicReference<>();
        CountDownLatch pumpDone = new CountDownLatch(1);
        try (BufferedWriter stdin =
                new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            Conversation convo = new Conversation() {
                @Override
                public void send(String line) {
                    try {
                        stdin.write(line);
                        stdin.write('\n');
                        stdin.flush();
                    } catch (IOException ignored) {
                        // Plugin is gone / pipe broken; the read loop will end.
                    }
                }

                @Override
                public void closeInput() {
                    try {
                        stdin.close();
                    } catch (IOException ignored) {
                        // Already closed is fine.
                    }
                }
            };
            // One-shot plugins never read stdin; close it so accidental System.in.readLine()
            // (confirm prompts in suite tests) gets EOF instead of hanging on an open pipe.
            if (closeStdinImmediately) {
                convo.closeInput();
            }
            Thread pump = Thread.ofVirtual().name("jk-worker-pump").start(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (abandoned.get()) continue; // orphan chatter after the job moved on
                        lastLineAt.set(System.currentTimeMillis());
                        if (line.startsWith(prefix)) {
                            onProtocol.accept(line.substring(prefix.length()), convo);
                        } else if (onPassthrough != null) {
                            onPassthrough.accept(line);
                        }
                    }
                } catch (IOException e) {
                    pumpError.set(e);
                } finally {
                    pumpDone.countDown();
                    try {
                        reader.close(); // pump owns the reader: closing it from another thread
                    } catch (IOException ignored) { // would block on the readLine monitor
                        // Already closed / process gone.
                    }
                }
            });
            while (true) {
                if (pumpDone.await(100, TimeUnit.MILLISECONDS)) break;
                if (!process.isAlive()) {
                    // Root is gone; let the pump drain buffered output and see EOF. If the grace
                    // elapses the write end is held by a reparented orphan we can neither
                    // enumerate nor wake — abandon the pump (it parks until the orphan exits,
                    // discarding whatever it reads) rather than wedging the job thread.
                    if (!pumpDone.await(ORPHAN_DRAIN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                        abandoned.set(true);
                        forceStop(process); // best effort: stragglers still visible + fd close
                    }
                    break;
                }
            }
            IOException e = pumpError.get();
            if (e != null && !abandoned.get()) {
                // Worker died or pipe closed mid-stream: bare "closed"). Prefer a
                // waitFor exit code over an opaque IOException when the process is already gone.
                if (isPipeClosed(e) && !process.isAlive()) {
                    return process.waitFor();
                }
                if (isPipeClosed(e)) {
                    forceStop(process);
                    int exit;
                    try {
                        exit = process.waitFor();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException(
                                "plugin pipe closed while waiting (interrupted); partial exit unknown", e);
                    }
                    throw new IOException("plugin pipe closed (worker exit " + exit + "): " + e.getMessage(), e);
                }
                throw e;
            }
        } finally {
            try {
                if (watchdog != null) watchdog.interrupt();
                if (process.isAlive() || hasLiveDescendant(process)) {
                    forceStop(process);
                }
            } finally {
                JobWorkers.unregister(process);
            }
        }
        return process.waitFor();
    }

    /**
     * After the root exits, how long the pump gets to drain buffered output before the job
     * concludes an orphan is holding the pipe and abandons the reader. Only the orphan case pays
     * it — a clean EOF releases the latch immediately.
     */
    private static final long ORPHAN_DRAIN_GRACE_MS = 5_000L;

    /**
     * Kill the worker and its descendants, then close the parent's read end so {@code readLine}
     * cannot stay blocked on an orphan still holding the write end of the pipe.
     */
    private static void forceStop(Process process) {
        JobWorkers.destroyTree(process);
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // Already closed / process gone.
        }
    }

    private static boolean hasLiveDescendant(Process process) {
        try {
            return process.descendants().anyMatch(java.lang.ProcessHandle::isAlive);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code IOException} messages like {@code closed} / {@code Stream closed} from broken pipes. */
    public static boolean isPipeClosed(IOException e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName().contains("Closed");
        }
        String lower = m.toLowerCase(Locale.ROOT);
        return lower.equals("closed")
                || lower.contains("stream closed")
                || lower.contains("pipe closed")
                || lower.contains("broken pipe");
    }
}
