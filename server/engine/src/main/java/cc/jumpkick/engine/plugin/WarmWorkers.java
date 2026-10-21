// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.BoundedLineReader;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Plugin workers kept up between runs ({@code PluginMain --serve}): a run hands an idle worker of
 * the same launch its spec, and only a launch with no idle worker forks one. A worker keeps what
 * its plugin caches between runs, a JIT that has seen the work before, and its loaded classes.
 *
 * <p><strong>Ownership.</strong> A worker belongs to the engine, not to the request that started
 * it, so it is forked outside the request's worker scope. Three owners end it: a reaper once it has
 * sat idle for the {@link WarmLinger linger}, the lease ledger when a waiting fork needs its memory
 * (after the run in flight), and the engine's shutdown. A run that the plugin answers with {@code
 * retire}, or that a cancel or an interrupt cuts short, ends its worker too: the next run starts a
 * fresh one.
 *
 * <p><strong>A launch</strong> is the whole command line and environment. Anything a running
 * worker cannot be given (heap, JVM flags, classpath, startup cache) is part of it, so two runs
 * that differ there never share a worker.
 *
 * <p>The worker's output is read on a platform thread, for the reason {@code KtsSession} gives: an
 * idle worker is silent for as long as the linger, and a virtual thread parked on that pipe would
 * hold its carrier the whole time.
 */
public final class WarmWorkers {

    private WarmWorkers() {}

    /**
     * What to run a spec on.
     *
     * @param name what the lease ledger and {@code jk engine status} call the worker
     * @param command the full served command line, ending with {@link PluginProtocol#SERVE}
     * @param prefix the plugin's protocol prefix
     * @param heapKey where the worker's peak is learned when it exits
     */
    public record Launch(String name, List<String> command, WorkerEnv env, String prefix, HeapScope.Key heapKey) {

        String key() {
            StringBuilder sb = new StringBuilder();
            for (String arg : command) sb.append(arg).append('\u0000');
            sb.append('\u0001').append(new TreeMap<>(env.environment()));
            return sb.toString();
        }
    }

    /** One run's lines: its protocol messages (prefix stripped) and everything else it printed. */
    public interface Listener {
        void protocol(String json);

        void output(String line);
    }

    /**
     * How a run ended. {@code exit} is the plugin's answer, or the worker's exit status when it did
     * not answer: it died, or was stopped for a cancel.
     */
    public record Outcome(int exit, boolean died) {}

    /** How often a waiting run looks at its cancel probe and at the worker's life. */
    private static final Duration POLL = Duration.ofMillis(50);

    /** How long a run waits for the worker's last lines once it has exited. */
    private static final Duration EXIT_GRACE = Duration.ofSeconds(5);

    /** Queued in place of a line when the worker's output ends. */
    private static final String EOF = "\u0000eof";

    private static final Clock CLOCK = Clock.SYSTEM;

    /** Idle workers by launch key, most recently used last. Guarded by itself. */
    private static final Map<String, Deque<Worker>> IDLE = new HashMap<>();

    /** Every live worker, idle or running: the engine's shutdown stops them all. */
    private static final Set<Worker> LIVE = ConcurrentHashMap.newKeySet();

    private static volatile boolean hookRegistered;

    /**
     * Run {@code spec} on an idle worker of {@code launch}, or a new one. Returns when the plugin
     * answers or the worker dies. {@code cancelled} is watched while the run is in flight; when it
     * flips the worker is stopped and the run ends {@linkplain Outcome#died died}.
     */
    public static Outcome run(Launch launch, Path spec, Listener listener, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        PluginLoader.sealNetworkPolicy(spec);
        String key = launch.key();
        Worker worker = takeIdle(key);
        if (worker == null) worker = start(launch, key);
        boolean keep = false;
        try {
            Outcome outcome = worker.request(spec, launch.prefix(), listener, cancelled);
            keep = !outcome.died();
            return outcome;
        } finally {
            release(worker, keep);
        }
    }

    /** Stop every worker now: the engine's shutdown, and the test seam. */
    public static void shutdown() {
        synchronized (IDLE) {
            IDLE.clear();
        }
        for (Worker w : List.copyOf(LIVE)) w.exit();
    }

    /** Test seam: idle workers across launches. */
    static int idleCount() {
        synchronized (IDLE) {
            int n = 0;
            for (Deque<Worker> d : IDLE.values()) n += d.size();
            return n;
        }
    }

    /** Test seam: the pids of every live worker. */
    static List<Long> livePids() {
        List<Long> pids = new ArrayList<>();
        for (Worker w : LIVE) {
            if (w.process.isAlive()) pids.add(w.process.pid());
        }
        return pids;
    }

    private static @Nullable Worker takeIdle(String key) {
        synchronized (IDLE) {
            Deque<Worker> idle = IDLE.get(key);
            while (idle != null && !idle.isEmpty()) {
                Worker w = idle.pollLast();
                if (w.process.isAlive()) {
                    if (idle.isEmpty()) IDLE.remove(key);
                    return w;
                }
            }
            if (idle != null) IDLE.remove(key);
            return null;
        }
    }

    /**
     * Back to the idle list when it may serve again, else asked to exit: a dead or retiring worker,
     * one the ledger asked for, and every worker when the linger is zero.
     */
    private static void release(Worker w, boolean keep) {
        boolean idle = keep && w.process.isAlive() && !w.retire && !w.yielding && WarmLinger.millis() > 0;
        if (idle) {
            synchronized (IDLE) {
                w.lastUsedNanos = CLOCK.nanos();
                IDLE.computeIfAbsent(w.key, k -> new ArrayDeque<>()).addLast(w);
            }
            w.armReaper();
            return;
        }
        w.exit();
    }

    /** Remove {@code w} from the idle list. True when it was there, so the caller owns ending it. */
    private static boolean unlistIdle(Worker w) {
        synchronized (IDLE) {
            Deque<Worker> idle = IDLE.get(w.key);
            if (idle == null || !idle.remove(w)) return false;
            if (idle.isEmpty()) IDLE.remove(w.key);
            return true;
        }
    }

    private static Worker start(Launch launch, String key) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(WorkerSession.detached(launch.command())).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(launch.env().environment());
        Worker[] started = new Worker[1];
        // Bound for the start only: the scope is what files the worker's peak when it exits.
        HeapScope.Key previous = HeapScope.bind(launch.heapKey());
        Process process;
        try {
            process = JobWorkers.startResident(pb, new WorkerLeases.Resident(launch.name(), () -> {
                Worker w = started[0];
                if (w != null) w.yieldLease();
            }));
        } finally {
            HeapScope.restore(previous);
        }
        Worker w = new Worker(key, process);
        started[0] = w;
        LIVE.add(w);
        registerShutdownHook();
        return w;
    }

    private static void registerShutdownHook() {
        if (hookRegistered) return;
        synchronized (WarmWorkers.class) {
            if (hookRegistered) return;
            hookRegistered = true;
            // Shutdown hook; reads no session.
            Runtime.getRuntime().addShutdownHook(new Thread(WarmWorkers::shutdown, "jk-warm-workers-shutdown"));
        }
    }

    /** One served worker process and the lines it has written that no run has read yet. */
    private static final class Worker {
        final String key;
        final Process process;
        private final BufferedWriter stdin;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        /** When its last run ended, on the monotonic clock. Guarded by {@link #IDLE}. */
        long lastUsedNanos = CLOCK.nanos();

        /** The plugin asked to retire after the run that just ended. */
        volatile boolean retire;

        /** The lease ledger wants this worker's memory; it exits after the run in flight. */
        volatile boolean yielding;

        private @Nullable Thread reaper;

        Worker(String key, Process process) {
            this.key = key;
            this.process = process;
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            BoundedLineReader out =
                    new BoundedLineReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Thread.ofPlatform().daemon().name("jk-warm-worker-reader").start(() -> {
                try {
                    String line;
                    while ((line = out.readLine()) != null) lines.put(line);
                } catch (IOException | InterruptedException e) {
                    // The pipe is gone or the engine is stopping; EOF below tells the run either way.
                } finally {
                    lines.add(EOF);
                }
            });
        }

        Outcome request(Path spec, String prefix, Listener listener, BooleanSupplier cancelled)
                throws InterruptedException {
            try {
                stdin.write("RUN " + spec.toAbsolutePath() + "\n");
                stdin.flush();
            } catch (IOException e) {
                return died();
            }
            try {
                while (true) {
                    String line = lines.poll(POLL.toMillis(), TimeUnit.MILLISECONDS);
                    if (line == null) {
                        if (cancelled.getAsBoolean()) {
                            kill();
                            return died();
                        }
                        if (!process.isAlive()) {
                            line = lines.poll(EXIT_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                            if (line == null) return died();
                        } else {
                            continue;
                        }
                    }
                    if (EOF.equals(line)) return died();
                    int at = line.indexOf(prefix);
                    if (at < 0) {
                        listener.output(line);
                        continue;
                    }
                    if (at > 0) listener.output(line.substring(0, at));
                    String json = line.substring(at + prefix.length());
                    String t = Jsonl.str(json, PluginProtocol.T);
                    if (PluginProtocol.READY.equals(t)) continue;
                    if (PluginProtocol.EXITED.equals(t)) {
                        retire = Jsonl.bool(json, "retire", false);
                        return new Outcome(Jsonl.intValue(json, "code", 1), false);
                    }
                    listener.protocol(json);
                }
            } catch (InterruptedException e) {
                // The run is abandoned mid-flight; its worker cannot be handed to the next one.
                kill();
                throw e;
            }
        }

        /** The worker is gone or going: its exit status, once it has one. */
        private Outcome died() throws InterruptedException {
            if (!process.waitFor(EXIT_GRACE.toMillis(), TimeUnit.MILLISECONDS)) kill();
            LIVE.remove(this);
            return new Outcome(process.isAlive() ? 1 : process.exitValue(), true);
        }

        void armReaper() {
            synchronized (this) {
                if (reaper != null && reaper.isAlive()) return;
                // Idle reaper of one warm worker; reads no session.
                reaper = Thread.ofVirtual().name("jk-warm-worker-reaper").start(this::reap);
            }
        }

        /**
         * Sleep until the linger could be over and look again: a run in between moves the deadline.
         * Only a worker still on the idle list is ended here; one taken for a run is that run's.
         */
        private void reap() {
            while (process.isAlive()) {
                long linger = TimeUnit.MILLISECONDS.toNanos(WarmLinger.millis());
                long wait;
                synchronized (IDLE) {
                    wait = linger - (CLOCK.nanos() - lastUsedNanos);
                }
                if (wait <= 0) {
                    if (unlistIdle(this)) exit();
                    return;
                }
                try {
                    Thread.sleep(Duration.ofNanos(wait));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        /** The ledger needs the memory: an idle worker exits now, a running one after its run. */
        void yieldLease() {
            yielding = true;
            if (unlistIdle(this)) exit();
        }

        /** Ask the worker to exit and wait briefly; one that does not go is killed. */
        void exit() {
            LIVE.remove(this);
            Thread t = reaper;
            if (t != null && t != Thread.currentThread()) t.interrupt();
            try {
                stdin.write("DONE\n");
                stdin.flush();
                if (!process.waitFor(5, TimeUnit.SECONDS)) kill();
            } catch (IOException e) {
                kill();
            } catch (InterruptedException e) {
                kill();
                Thread.currentThread().interrupt();
            }
        }

        void kill() {
            LIVE.remove(this);
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.debug("warm worker " + process.pid() + " stopped");
        }
    }
}
