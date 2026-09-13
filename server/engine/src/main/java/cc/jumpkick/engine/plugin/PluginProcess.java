// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

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
import java.util.Objects;
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

    /**
     * The conversation ended because the parent's own protocol handler threw. The worker was
     * stopped on the parent's account, so its exit code says nothing; the handler's exception is
     * the cause, and a caller that reports worker deaths tells this one apart by type.
     */
    public static final class HandlerFailure extends IOException {
        private static final long serialVersionUID = 1L;

        public HandlerFailure(RuntimeException handler) {
            super("plugin protocol handler threw " + handler, handler);
        }

        /** The handler's own exception. */
        public RuntimeException handler() {
            return (RuntimeException) Objects.requireNonNull(getCause());
        }
    }

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
        return run(command, WorkerEnv.strict(), prefix, onProtocol, onPassthrough);
    }

    /** As {@link #run(List, String, Consumer, Consumer)} with the child's {@link WorkerEnv}. */
    public static int run(
            List<String> command,
            WorkerEnv env,
            String prefix,
            Consumer<String> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return run(command, env, null, prefix, onProtocol, onPassthrough);
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
            WorkerEnv env,
            @Nullable Path workDir,
            String prefix,
            Consumer<String> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(
                command,
                env,
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
        return converse(command, WorkerEnv.strict(), prefix, onProtocol, onPassthrough);
    }

    /** As {@link #converse(List, String, BiConsumer, Consumer)} with the child's {@link WorkerEnv}. */
    public static int converse(
            List<String> command,
            WorkerEnv env,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(command, env, null, prefix, onProtocol, onPassthrough);
    }

    /**
     * As {@link #converse(List, WorkerEnv, String, BiConsumer, Consumer)} with an optional working
     * directory (Quarkus {@code @QuarkusTest} resolves the project from the process cwd).
     */
    public static int converse(
            List<String> command,
            WorkerEnv env,
            @Nullable Path workDir,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(command, env, workDir, prefix, onProtocol, onPassthrough, false);
    }

    private static int converse(
            List<String> command,
            WorkerEnv env,
            @Nullable Path workDir,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough,
            boolean closeStdinImmediately)
            throws IOException, InterruptedException {
        return converse(command, env, workDir, prefix, onProtocol, onPassthrough, closeStdinImmediately, 0L);
    }

    /**
     * As {@link #converse(List, String, BiConsumer, Consumer)} but WITHOUT taking a process-lifetime
     * worker slot. The caller — the long-lived Zinc pull session — meters {@link PluginSlots} itself,
     * once per in-flight COMPILE/PLAN exchange, so an idle resident worker does not pin a permit for
     * the whole job and deadlock nested forks such as the test runner.
     */
    public static int converseNoSlot(
            List<String> command,
            WorkerEnv env,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(builder(command, env), prefix, onProtocol, onPassthrough, false, 0L);
    }

    /**
     * As {@link #converse(List, WorkerEnv, Path, String, BiConsumer, Consumer)} with an inactivity
     * watchdog: when the child emits no output line for {@code idleTimeoutMs}, it is
     * force-killed (process tree) and the conversation ends with its (non-zero) exit code.
     * {@code 0} = no watchdog — compiler workers are legitimately silent for long stretches;
     * only callers whose protocol guarantees a heartbeat-ish cadence (the test runner's
     * per-test events) should pass a window.
     */
    public static int converse(
            List<String> command,
            WorkerEnv env,
            @Nullable Path workDir,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough,
            boolean closeStdinImmediately,
            long idleTimeoutMs)
            throws IOException, InterruptedException {
        ProcessBuilder pb = builder(command, env);
        if (workDir != null && Files.isDirectory(workDir)) pb.directory(workDir.toFile());
        // Hold a worker slot for the child's whole lifetime so no more than the
        // memory plan's parallelism run at once (open gate when unconfigured).
        try (PluginSlots.Lease lease = PluginSlots.acquire()) {
            return converse(pb, prefix, onProtocol, onPassthrough, closeStdinImmediately, idleTimeoutMs);
        }
    }

    /**
     * The one {@code ProcessBuilder} a worker is forked from: stderr merged, and the environment
     * replaced wholesale by what {@code env} composes — never the engine's own plus extras.
     */
    private static ProcessBuilder builder(List<String> command, WorkerEnv env) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> environment = pb.environment();
        environment.clear();
        environment.putAll(env.environment());
        return pb;
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
        AtomicReference<RuntimeException> handlerError = new AtomicReference<>();
        CountDownLatch pumpDone = new CountDownLatch(1);
        try (BufferedWriter stdin =
                new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            Conversation convo = conversationOver(stdin);
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
                        // The protocol shares the child's stdout with everything else the child
                        // prints. Output that ends without a newline — a progress line, a
                        // library's banner — glues the next protocol line onto itself, and a
                        // marker only recognised at column 0 then loses that event: the test
                        // whose finish it announced was never recorded. The marker is found
                        // wherever it sits; what precedes it is the chatter it was glued to.
                        int at = line.indexOf(prefix);
                        try {
                            if (at < 0) {
                                if (onPassthrough != null) onPassthrough.accept(line);
                                continue;
                            }
                            if (at > 0 && onPassthrough != null) onPassthrough.accept(line.substring(0, at));
                            onProtocol.accept(line.substring(at + prefix.length()), convo);
                        } catch (RuntimeException e) {
                            // A handler that throws is the parent's bug, and the conversation ends
                            // on it by name. Left to escape, it would only kill this thread: the
                            // worker stays alive with nobody reading, the job force-stops it as a
                            // hung child, and the kill's exit code stands in for the real cause.
                            handlerError.set(e);
                            return;
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
            RuntimeException handler = handlerError.get();
            if (handler != null) {
                // The finally below stops the worker; the exit code of a kill we asked for says
                // nothing, so the diagnostic carries the handler's own exception instead.
                throw new HandlerFailure(handler);
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

    /** The handle a protocol handler talks back through: one line per send, flushed, on the child's stdin. */
    private static Conversation conversationOver(BufferedWriter stdin) {
        return new Conversation() {
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
