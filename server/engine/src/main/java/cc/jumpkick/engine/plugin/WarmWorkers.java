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
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Plugin workers kept up between runs ({@code PluginMain --serve}): a run hands an idle worker of
 * the same launch its spec, and only a launch with no idle worker forks one. A worker keeps what
 * its plugin caches between runs, a JIT that has seen the work before, and its loaded classes.
 *
 * <p><strong>Ownership.</strong> A worker belongs to the engine, not to the request that started
 * it, so it is forked outside the request's worker scope. Four owners end it: a reaper once it has
 * sat idle for the {@link WarmLinger linger}, the lease ledger when a waiting fork needs its memory
 * (after the run in flight), {@link HostPressure} when the host is short of memory (only while it
 * is idle), and the engine's shutdown. A run that the plugin answers with {@code
 * retire}, or that a cancel or an interrupt cuts short, ends its worker too: the next run starts a
 * fresh one.
 *
 * <p><strong>A launch</strong> is the whole command line and environment. Anything a running
 * worker cannot be given (JVM flags, classpath) is part of it, so two runs that differ there never
 * share a worker. The heap and the startup cache are the exceptions: an idle worker of the same
 * launch with at least the heap a run asks for serves it, whether or not it mapped a cache, and one
 * that a bigger idle sibling covers exits instead of idling, so a tool settles on one warm worker
 * at the largest heap it needed.
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
     * @param heapKey where the worker's peak is learned when it exits; {@code null} learns nothing
     */
    public record Launch(
            String name, List<String> command, WorkerEnv env, String prefix, HeapScope.@Nullable Key heapKey) {

        /**
         * The launch without its heap and startup-cache flags: workers of one key differ at most in
         * heap, and in whether they started by mapping a cache.
         */
        String key() {
            StringBuilder sb = new StringBuilder();
            for (String arg : command) {
                if (!WorkerLeases.heapFlag(arg) && !WorkerAotCache.mappingFlag(arg))
                    sb.append(arg).append('\u0000');
            }
            sb.append('\u0001').append(new TreeMap<>(env.environment()));
            return sb.toString();
        }

        /** The {@code -Xmx} asked for, or {@code -1} when the command names none. */
        long heap() {
            return WorkerLeases.parseXmx(command);
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

    /** The most recently idled worker of each launch name; under CI a kept one outlives the linger. */
    private static final Map<String, Worker> LATEST = new ConcurrentHashMap<>();

    /**
     * Run {@code spec} on an idle worker of {@code launch}, or a new one. Returns when the plugin
     * answers or the worker dies. {@code cancelled} is watched while the run is in flight; when it
     * flips the worker is stopped and the run ends {@linkplain Outcome#died died}.
     */
    public static Outcome run(Launch launch, Path spec, Listener listener, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        PluginLoader.sealNetworkPolicy(spec);
        String key = launch.key();
        Worker worker = takeIdle(key, launch.heap());
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

    /** End every idle worker that has been idle at least {@code minIdle}; how many. */
    static int shedIdle(Duration minIdle) {
        List<Worker> shed = new ArrayList<>();
        long now = CLOCK.nanos();
        synchronized (IDLE) {
            for (Deque<Worker> idle : IDLE.values()) {
                for (Worker w : idle) {
                    if (now - w.lastUsedNanos >= minIdle.toNanos()) shed.add(w);
                }
                idle.removeAll(shed);
            }
            IDLE.values().removeIf(Deque::isEmpty);
        }
        for (Worker w : shed) w.exit();
        return shed.size();
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

    /**
     * The idle worker of {@code key} with the smallest heap that is at least {@code heap}. A command
     * that names no heap takes only a worker that names none.
     */
    private static @Nullable Worker takeIdle(String key, long heap) {
        synchronized (IDLE) {
            Deque<Worker> idle = IDLE.get(key);
            if (idle == null) return null;
            idle.removeIf(w -> !w.process.isAlive());
            Worker best = null;
            for (Worker w : idle) {
                if (!fits(w.heap, heap)) continue;
                if (best == null || w.heap < best.heap) best = w;
            }
            if (best != null) idle.remove(best);
            if (idle.isEmpty()) IDLE.remove(key);
            return best;
        }
    }

    /** Whether a worker started with {@code have} can serve a run that asks for {@code want}. */
    static boolean fits(long have, long want) {
        return want < 0 ? have < 0 : have >= want;
    }

    /**
     * Back to the idle list when it may serve again, else asked to exit: a dead or retiring worker,
     * one the ledger asked for, and every worker when the linger is zero.
     */
    private static void release(Worker w, boolean keep) {
        boolean idle = keep
                && w.process.isAlive()
                && !w.retire
                && !w.yielding
                && (WarmLinger.millis() > 0 || WarmLinger.keepsLatest(w.name));
        if (!idle) {
            w.exit();
            return;
        }
        List<Worker> covered = new ArrayList<>();
        synchronized (IDLE) {
            Deque<Worker> siblings = IDLE.computeIfAbsent(w.key, k -> new ArrayDeque<>());
            w.lastUsedNanos = CLOCK.nanos();
            siblings.addLast(w);
            if (w.heap >= 0) {
                long largest = w.heap;
                for (Worker s : siblings) largest = Math.max(largest, s.heap);
                for (Worker s : siblings) {
                    if (s.heap >= 0 && s.heap < largest) covered.add(s);
                }
                siblings.removeAll(covered);
            }
        }
        // A bigger idle sibling serves whatever these would: they exit rather than hold a lease.
        for (Worker s : covered) s.exit();
        if (covered.contains(w)) return;
        Worker displaced = LATEST.put(w.name, w);
        // A worker CI kept as the latest is now held by the linger: its reaper looks again now.
        if (displaced != null && displaced != w) displaced.wake();
        w.armReaper();
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
        HeapScope.Key heapKey = launch.heapKey();
        HeapScope.@Nullable Key previous = heapKey != null ? HeapScope.bind(heapKey) : HeapScope.get();
        Process process;
        try {
            process = JobWorkers.startResident(pb, new WorkerLeases.Resident(launch.name(), () -> {
                Worker w = started[0];
                if (w != null) w.yieldLease();
            }));
        } finally {
            HeapScope.restore(previous);
        }
        Worker w = new Worker(launch.name(), key, launch.heap(), process);
        started[0] = w;
        LIVE.add(w);
        registerShutdownHook();
        HostPressure.register("warm plugin workers", WarmWorkers::shedIdle);
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
        final String name;
        final String key;

        /** The {@code -Xmx} it was started with, or {@code -1}. */
        final long heap;

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

        Worker(String name, String key, long heap, Process process) {
            this.name = name;
            this.key = key;
            this.heap = heap;
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
                if (wait <= 0 && kept()) {
                    wait = Math.max(linger, TimeUnit.MINUTES.toNanos(1));
                } else if (wait <= 0) {
                    if (unlistIdle(this)) exit();
                    return;
                }
                LockSupport.parkNanos(this, wait);
                if (Thread.currentThread().isInterrupted()) return;
            }
        }

        /** Has the reaper measure the linger again now rather than when its sleep ends. */
        void wake() {
            Thread t = reaper;
            if (t != null) LockSupport.unpark(t);
        }

        /** Under CI, the most recently used worker of a kept tool, which the linger does not end. */
        private boolean kept() {
            return WarmLinger.keepsLatest(name) && LATEST.get(name) == this;
        }

        /** The ledger needs the memory: an idle worker exits now, a running one after its run. */
        void yieldLease() {
            yielding = true;
            if (unlistIdle(this)) exit();
        }

        /** Ask the worker to exit and wait briefly; one that does not go is killed. */
        void exit() {
            LIVE.remove(this);
            LATEST.remove(name, this);
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
            LATEST.remove(name, this);
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
