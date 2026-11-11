// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.BoundedLineReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Parent-side child-JVM plugin driver: fork, split protocol lines from chatter, wait for exit.
 * {@link #run} is fire-and-read; {@link #converse} is two-way, its commands sent over a {@link
 * WorkerCommands} channel. Every worker starts with its stdin closed.
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

    /** A handle for talking back to a running plugin over its command channel. */
    public interface Conversation {
        /** Send one command line to the plugin (a newline is appended and flushed). */
        void send(String line);

        /** Close the plugin's command channel, signalling end-of-input (EOF). */
        void closeInput();

        /**
         * Send {@code line} only if the plugin is connected now; false, with nothing sent, when it is
         * not. Never waits for the connection, so a caller tearing a worker down cannot hang on one
         * that never connected.
         */
        default boolean offer(String line) {
            send(line);
            return true;
        }
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
        return run(command, env, workDir, prefix, onProtocol, onPassthrough, LearnedHeaps.engine());
    }

    /** As {@link #run(List, WorkerEnv, Path, String, Consumer, Consumer)}, recording learned peaks on {@code heaps}. */
    public static int run(
            List<String> command,
            WorkerEnv env,
            @Nullable Path workDir,
            String prefix,
            Consumer<String> onProtocol,
            @Nullable Consumer<String> onPassthrough,
            LearnedHeaps heaps)
            throws IOException, InterruptedException {
        ProcessBuilder pb = builder(command, env);
        if (workDir != null && Files.isDirectory(workDir)) pb.directory(workDir.toFile());
        return fork(
                pb,
                prefix,
                onProtocol,
                onPassthrough,
                null,
                0L,
                (p, heap) -> JobWorkers.start(p, heap, heaps),
                ORPHAN_DRAIN_GRACE_MS,
                process -> null);
    }

    /**
     * Fork {@code command} and drive a two-way conversation: each protocol line is delivered to
     * {@code onProtocol} along with a {@link Conversation} for sending commands back to the plugin. The
     * commands travel on a {@link WorkerCommands} channel {@linkplain WorkerCommands#attach attached}
     * to {@code command}; the plugin's stdin is closed.
     *
     * <p>Reading stdout and sending commands both happen on the calling thread, so the plugin must
     * alternate (await input → emit → await input) rather than flood stdout while blocked on input,
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
        return converse(command, env, workDir, prefix, onProtocol, onPassthrough, 0L, LearnedHeaps.engine());
    }

    /**
     * As {@link #converse(List, WorkerEnv, String, BiConsumer, Consumer)} for a worker whose caller
     * keeps the process for many exchanges. The memory lease is still taken when the process is
     * forked and held until it exits; an idle compiler lane exits when another fork is waiting.
     */
    public static int converseNoSlot(
            List<String> command,
            WorkerEnv env,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(
                builder(command, env),
                prefix,
                onProtocol,
                onPassthrough,
                0L,
                LearnedHeaps.engine(),
                ORPHAN_DRAIN_GRACE_MS);
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
            long idleTimeoutMs)
            throws IOException, InterruptedException {
        return converse(command, env, workDir, prefix, onProtocol, onPassthrough, idleTimeoutMs, LearnedHeaps.engine());
    }

    /** As {@link #converse(List, WorkerEnv, Path, String, BiConsumer, Consumer, long)}, recording learned peaks on {@code heaps}. */
    public static int converse(
            List<String> command,
            WorkerEnv env,
            @Nullable Path workDir,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough,
            long idleTimeoutMs,
            LearnedHeaps heaps)
            throws IOException, InterruptedException {
        ProcessBuilder pb = builder(command, env);
        if (workDir != null && Files.isDirectory(workDir)) pb.directory(workDir.toFile());
        return converse(pb, prefix, onProtocol, onPassthrough, idleTimeoutMs, heaps, ORPHAN_DRAIN_GRACE_MS);
    }

    /** As the watchdog overload with the orphan drain grace given in millis (tests). */
    static int converse(
            List<String> command,
            WorkerEnv env,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            long idleTimeoutMs,
            long orphanDrainGraceMs)
            throws IOException, InterruptedException {
        return converse(
                builder(command, env),
                prefix,
                onProtocol,
                null,
                idleTimeoutMs,
                LearnedHeaps.engine(),
                orphanDrainGraceMs);
    }

    /** How a fork is started and leased. Production is {@link JobWorkers#start(ProcessBuilder, JvmOptions.HeapChoice, LearnedHeaps)}. */
    @FunctionalInterface
    public interface Starter {
        Process start(ProcessBuilder pb, JvmOptions.HeapChoice heap) throws IOException;
    }

    /**
     * Fork {@code command} for a worker that answers on its command channel instead of its stdout.
     * {@code onOpen} receives the channel before the fork, for the commands the caller sends; each
     * line the worker writes back on it goes to {@code onFrame}, from a platform thread of its own
     * that does nothing else, so a caller that never blocks in {@code onFrame} always hears the
     * worker. Everything on stdout and stderr is {@code onPassthrough}'s. {@code starter} forks and
     * leases the process. Returns the exit code once the process and its output have ended.
     *
     * <p>{@code env} is composed when the fork happens. A caller forking on a thread of its own for
     * a request composes the request's environment on the request's thread and passes it whole
     * ({@link WorkerEnv#with}), or the worker would read the forking thread's request instead.
     *
     * <p>A frame handler that throws ends the conversation as a {@link HandlerFailure}: the worker is
     * stopped, because nobody would read what it answers next.
     */
    public static int converseOnChannel(
            List<String> command,
            WorkerEnv env,
            Starter starter,
            Consumer<Conversation> onOpen,
            Consumer<String> onFrame,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        ProcessBuilder pb = builder(command, env);
        WorkerCommands commands = WorkerCommands.open();
        commands.attach(pb);
        onOpen.accept(commands);
        AtomicReference<RuntimeException> frameError = new AtomicReference<>();
        try {
            int exit = fork(
                    pb,
                    "\u0000",
                    line -> {
                        if (onPassthrough != null) onPassthrough.accept(line);
                    },
                    onPassthrough,
                    commands,
                    0L,
                    starter,
                    ORPHAN_DRAIN_GRACE_MS,
                    process -> readFrames(commands, process, onFrame, frameError));
            RuntimeException handler = frameError.get();
            if (handler != null) throw new HandlerFailure(handler);
            return exit;
        } finally {
            commands.close();
        }
    }

    /**
     * The channel reader of {@link #converseOnChannel}: every line the worker answers, in order, until
     * the channel closes. A handler that throws stops the worker; its exception is the conversation's
     * failure. A worker still running two seconds after its channel ends is stopped too.
     */
    private static Thread readFrames(
            WorkerCommands commands,
            Process process,
            Consumer<String> onFrame,
            AtomicReference<RuntimeException> error) {
        return SessionContext.startPlatform("jk-worker-frames", () -> {
            try {
                BufferedReader frames = commands.input();
                String line;
                while ((line = frames.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    onFrame.accept(line);
                }
            } catch (IOException e) {
                // The channel closed or never opened: the process's own end says why.
            } catch (RuntimeException e) {
                error.set(e);
                forceStop(process);
                return;
            }
            // Nobody hears the worker any more. One that does not exit on its own is stopped, so
            // whatever it was running is reported as its death instead of waiting forever.
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) forceStop(process);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                forceStop(process);
            }
        });
    }

    /**
     * The one {@code ProcessBuilder} a worker is forked from: stderr merged, and the environment
     * replaced wholesale by what {@code env} composes — never the engine's own plus extras.
     */
    public static ProcessBuilder builder(List<String> command, WorkerEnv env) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> environment = pb.environment();
        environment.clear();
        environment.putAll(env.environment());
        return pb;
    }

    /** Open the conversation's channel, name it in the worker's arguments, and fork the worker. */
    private static int converse(
            ProcessBuilder pb,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough,
            long idleTimeoutMs,
            LearnedHeaps heaps,
            long orphanDrainGraceMs)
            throws IOException, InterruptedException {
        WorkerCommands commands = WorkerCommands.open();
        commands.attach(pb);
        try {
            return fork(
                    pb,
                    prefix,
                    json -> onProtocol.accept(json, commands),
                    onPassthrough,
                    commands,
                    idleTimeoutMs,
                    (p, heap) -> JobWorkers.start(p, heap, heaps),
                    orphanDrainGraceMs,
                    process -> null);
        } finally {
            commands.close();
        }
    }

    /**
     * Fork {@code pb} with its stdin closed, pump its output until it ends, and return its exit code.
     * {@code commands}, when the worker has a channel, is closed when the pump ends, so a worker that
     * waits for its next command reads end-of-input and exits.
     */
    private static int fork(
            ProcessBuilder pb,
            String prefix,
            Consumer<String> onProtocol,
            @Nullable Consumer<String> onPassthrough,
            @Nullable WorkerCommands commands,
            long idleTimeoutMs,
            Starter starter,
            long orphanDrainGraceMs,
            Function<Process, @Nullable Thread> onStarted)
            throws IOException, InterruptedException {
        // Every worker forks through here, so this is where it loses the engine's terminal.
        pb.command(WorkerSession.detached(pb.command()));
        // Read the heap's provenance before an argfile shorten hides the flags that say who chose it.
        JvmOptions.HeapChoice heap = JvmOptions.HeapChoice.inspect(pb.command());
        // Windows caps the command line; a long one launches through an argfile instead.
        WorkerArgfile argfile = WorkerArgfile.shorten(pb.command());
        pb.command(argfile.command());
        Process process;
        try {
            process = starter.start(pb, heap);
        } catch (IOException e) {
            argfile.delete();
            throw e;
        }
        Thread sideReader = onStarted.apply(process);
        // Commands, if any, travel on their own channel: code the worker runs that reads System.in,
        // and every child that inherits it, reads end-of-input instead of hanging on an open pipe.
        closeStdin(process);
        final AtomicLong lastLineAt = new AtomicLong(Clock.SYSTEM.millis());
        Thread watchdog = idleTimeoutMs > 0 ? startWatchdog(process, lastLineAt, idleTimeoutMs) : null;
        // Bounded like the client socket: a worker emitting an unbounded line must not OOM the
        // engine. No idle timeout — a compiling worker is legitimately silent for long stretches.
        //
        // The pump runs on a platform thread of its own, for two reasons. This (job) thread can
        // give up on the pipe: on Linux a root that exits leaving a reparented child holding the
        // stdout write end produces neither EOF nor a visible descendant — descendants() of a
        // dead process is empty, and closing the fd does not wake a blocked native pipe read —
        // so the job thread waits root-exit + a drain grace, then abandons the reader instead of
        // hanging forever. And the pump keeps reading whatever the virtual-thread scheduler is
        // busy with: a pump on a virtual thread waits for a carrier while a plan's work holds
        // every one, the fork blocks on a full pipe or exits with its last events unread, and the
        // job either never ends or gives the pipe up with those events still in it.
        //
        // Both threads start under the calling request's session: the handler the pump invokes
        // runs on the pump, and a bare thread would hand it the process-default session.
        BufferedReader reader =
                new BoundedLineReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        AtomicBoolean abandoned = new AtomicBoolean();
        AtomicReference<IOException> pumpError = new AtomicReference<>();
        AtomicReference<RuntimeException> handlerError = new AtomicReference<>();
        CountDownLatch pumpDone = new CountDownLatch(1);
        try (commands) {
            Thread pump = SessionContext.startPlatform("jk-worker-pump", () -> {
                try {
                    while (true) {
                        // Once the job has given the pipe up, what the fork already wrote is still
                        // delivered — those lines are in the pipe now — and only a read that would
                        // wait on the orphan holding the write end is not made.
                        if (abandoned.get() && !reader.ready()) break;
                        String line = reader.readLine();
                        if (line == null) break;
                        lastLineAt.set(Clock.SYSTEM.millis());
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
                            onProtocol.accept(line.substring(at + prefix.length()));
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
                    // enumerate nor wake — abandon the pump rather than wedge the job thread:
                    // the stragglers still visible are killed, the pump gets the same grace again
                    // to deliver the lines the fork wrote before it died, and only then is the
                    // parent's read end closed (best effort; a pump parked on the orphan's pipe
                    // stays parked until the orphan exits, and reads nothing more for the job).
                    if (!pumpDone.await(orphanDrainGraceMs, TimeUnit.MILLISECONDS)) {
                        abandoned.set(true);
                        JobWorkers.destroyTree(process);
                        pumpDone.await(orphanDrainGraceMs, TimeUnit.MILLISECONDS);
                        forceStop(process);
                    }
                    break;
                }
            }
            // A worker that answers on its channel wrote its last frames there before it exited; the
            // channel closes when this block ends, so its reader finishes first. The worker's end of
            // the socket closed with the process, so the reader is at end of input already.
            if (sideReader != null) sideReader.join(orphanDrainGraceMs);
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
                    throw new IOException(
                            "plugin pipe closed (worker " + WorkerContainment.failure(exit, "exit " + exit) + "): "
                                    + e.getMessage(),
                            e);
                }
                throw e;
            }
        } finally {
            try {
                if (watchdog != null) watchdog.interrupt();
                if (process.isAlive() || hasLiveDescendant(process)) {
                    // A worker whose command channel we closed is already exiting. Killing it now records
                    // SIGKILL, and a compiler lane that left to free memory looks like a crash.
                    try {
                        if (!process.waitFor(1, TimeUnit.SECONDS)) forceStop(process);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        forceStop(process);
                    }
                }
            } finally {
                JobWorkers.unregister(process);
                argfile.delete();
            }
        }
        return process.waitFor();
    }

    /** Give {@code process} end-of-input on its stdin; commands never travel there. */
    public static void closeStdin(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Already closed: the process is gone.
        }
    }

    /**
     * After the root exits, how long the pump gets to drain buffered output before the job
     * concludes an orphan is holding the pipe and abandons the reader — and, once abandoned, how
     * long it gets to deliver the lines already in the pipe before the read end is closed. Only the
     * orphan case pays either — a clean EOF releases the latch immediately.
     */
    private static final long ORPHAN_DRAIN_GRACE_MS = 5_000L;

    /**
     * Kill the worker and its descendants, then close the parent's read end so {@code readLine}
     * cannot stay blocked on an orphan still holding the write end of the pipe.
     */
    /**
     * The inactivity watchdog: once {@code lastLineAt} stands still for {@code idleTimeoutMs}, the
     * child and its descendants are force-killed. A daemon platform thread, so the kill it owes
     * fires however busy the virtual-thread scheduler is — a plan spinning on every carrier would
     * otherwise delay it for as long as the spin lasts. Interrupting it ends the watch.
     */
    static Thread startWatchdog(Process process, AtomicLong lastLineAt, long idleTimeoutMs) {
        return SessionContext.startPlatform("jk-worker-watchdog", () -> {
            while (process.isAlive() || hasLiveDescendant(process)) {
                long idle = Clock.SYSTEM.millis() - lastLineAt.get();
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
    }

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
