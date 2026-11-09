// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginReply;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The resident compiler worker's loop: many compiles at once in one JVM, each on a thread of a
 * fixed pool, with the engine's commands read and its answers written on the command channel.
 *
 * <p>One JVM for every module of a build is the point. Each compile shares the JIT that every earlier
 * compile warmed, the jar indexes javac's file manager keeps, Zinc's stamps and the processor class
 * loaders, where a JVM per module paid for all of them again. javac runs one compile on one thread,
 * so the pool has one thread per core the JVM sees — {@link Runtime#availableProcessors()}, which
 * honours a cgroup quota — unless the engine's {@code hello} names a count. Its threads are platform
 * threads with {@link #STACK_BYTES} of stack: javac recurses as deep as the source nests.
 *
 * <p>Every frame about a compile carries that compile's id, so the engine attributes diagnostics,
 * provenance and the result to the compile that produced them however many are interleaved. A
 * compile that throws answers {@code error} with its id and the worker goes on; only the death of
 * the JVM (an out-of-memory exit, a processor that calls {@code System.exit}) takes every compile in
 * flight with it, and the engine runs those again. What the compiles print is not attributable to
 * one of them and goes to stdout and stderr, which the engine keeps as the worker's transcript.
 */
final class CompileServer {

    /** Stack of each compile thread: javac attributes deeply nested expressions recursively. */
    static final long STACK_BYTES = 8L << 20;

    /** How often a {@link PluginProtocol#HEAP} reading goes out while the worker is up. */
    static final long HEAP_EVERY_MILLIS = 1_000L;

    /** Runs one compile or plan from a spec file, sending its frames; returns its exit code. */
    @FunctionalInterface
    interface Compiler {
        int run(boolean plan, Path spec, Consumer<String> emit) throws Exception;
    }

    private final BufferedReader in;
    private final Consumer<String> send;
    private final Compiler compiler;
    private final ConcurrentHashMap<Long, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicInteger worst = new AtomicInteger();
    private final HeapGauge gauge = new HeapGauge(Clock.SYSTEM);
    private @Nullable ExecutorService pool;

    /** Cancels that arrived before their compile, oldest first; a few thousand at most. */
    private final Set<Long> earlyCancels = new LinkedHashSet<>();

    private static final int EARLY_CANCELS_KEPT = 4096;

    private final ExecutorService trimmer = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "jk-javac-trim");
        t.setDaemon(true);
        return t;
    });
    private @Nullable Thread reporter;

    CompileServer(BufferedReader in, Consumer<String> send, Compiler compiler) {
        this.in = in;
        this.send = send;
        this.compiler = compiler;
    }

    /**
     * Serve until {@code done} or the end of the engine's input, then let every compile in flight
     * finish. Returns the highest exit code any compile returned.
     */
    int serve() throws IOException, InterruptedException {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.strip();
                if (cmd.isEmpty()) continue;
                if (!handle(cmd)) break;
            }
        } finally {
            drain();
        }
        return worst.get();
    }

    /** One command; false when it is {@code done}. */
    private boolean handle(String cmd) {
        String op = Jsonl.str(cmd, PluginProtocol.OP);
        if (op == null) {
            send.accept(PluginReply.error("usage", "not a command: " + cmd));
            return true;
        }
        switch (op) {
            case PluginProtocol.HELLO -> hello(Jsonl.intValue(cmd, "threads", 0));
            case PluginProtocol.OP_COMPILE, PluginProtocol.OP_PLAN -> submit(op, cmd);
            case PluginProtocol.OP_CANCEL -> cancel(Jsonl.longValue(cmd, PluginProtocol.ID, -1));
            case PluginProtocol.OP_TRIM -> trim();
            case PluginProtocol.OP_DONE -> {
                return false;
            }
            default -> send.accept(PluginReply.error("unknown", op));
        }
        return true;
    }

    /** Size the pool, start the heap readings, and say who this worker is. */
    private void hello(int threads) {
        pool(threads);
        if (reporter == null) startReporter();
        send.accept(JsonFields.object()
                .string(PluginProtocol.T, PluginProtocol.HELLO)
                .number("threads", threadCount)
                .number("pid", ProcessHandle.current().pid())
                .string("jdk", Runtime.version().toString())
                .finish());
    }

    private int threadCount;

    /** The compile pool, made on first use with {@code threads} threads, or one per core when 0. */
    private ExecutorService pool(int threads) {
        ExecutorService p = pool;
        if (p != null) return p;
        int n = threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors());
        AtomicInteger count = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread t = new Thread(null, task, "jk-javac-" + count.incrementAndGet(), STACK_BYTES);
            t.setDaemon(true);
            return t;
        };
        p = Executors.newFixedThreadPool(n, factory);
        pool = p;
        threadCount = n;
        return p;
    }

    private void submit(String op, String cmd) {
        long id = Jsonl.longValue(cmd, PluginProtocol.ID, -1);
        String spec = Jsonl.str(cmd, "spec");
        if (id < 0 || spec == null || spec.isBlank()) {
            send.accept(PluginReply.error("usage", "op compile|plan with an id and a spec path, not: " + cmd));
            return;
        }
        boolean plan = PluginProtocol.OP_PLAN.equals(op);
        // A cancel can reach the worker before the compile it names; the compile then ends at its
        // first checkpoint, before it reads its spec.
        AtomicBoolean cancelled = new AtomicBoolean(takeEarlyCancel(id));
        running.put(id, cancelled);
        inflight.incrementAndGet();
        pool(0).execute(() -> run(id, plan, Path.of(spec), cancelled));
    }

    /** One compile on a pool thread. Every way it can end answers once, with its id. */
    private void run(long id, boolean plan, Path spec, AtomicBoolean cancelled) {
        Consumer<String> emit = json -> send.accept(withId(json, id));
        CompileCancel.bind(cancelled);
        try {
            int code = compiler.run(plan, spec, emit);
            worst.accumulateAndGet(code, Math::max);
        } catch (Throwable t) {
            if (cancelled.get() || causedByCancel(t)) {
                emit.accept(JsonFields.object()
                        .string(PluginProtocol.T, PluginProtocol.CANCELLED)
                        .finish());
            } else {
                // The trace goes to the transcript; the frame names the failure for the compile.
                System.err.println("jk-java-compiler: compile " + id + " of " + spec + " failed");
                t.printStackTrace();
                emit.accept(PluginReply.error("failed", String.valueOf(t)));
                worst.accumulateAndGet(Exit.FAILURE, Math::max);
            }
        } finally {
            CompileCancel.unbind();
            running.remove(id);
            inflight.decrementAndGet();
        }
    }

    private static boolean causedByCancel(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof CompileCancel.Cancelled) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    /** {@code json} with {@code "id":n} first, where a reader scanning for the key meets it before any value. */
    static String withId(String json, long id) {
        String head = JsonFields.object().number(PluginProtocol.ID, id).finish();
        return head.substring(0, head.length() - 1) + ',' + json.substring(1);
    }

    /**
     * Stop compile {@code id}. One not running yet is remembered, so a cancel that overtook its
     * compile on the way still wins when the compile arrives.
     */
    private void cancel(long id) {
        AtomicBoolean flag = running.get(id);
        if (flag != null) {
            flag.set(true);
            return;
        }
        synchronized (earlyCancels) {
            earlyCancels.add(id);
            while (earlyCancels.size() > EARLY_CANCELS_KEPT)
                earlyCancels.remove(earlyCancels.iterator().next());
        }
        // The compile may have registered between the look and the add.
        AtomicBoolean late = running.get(id);
        if (late != null) late.set(true);
    }

    /** Whether a cancel for {@code id} arrived before it, forgetting it either way. */
    private boolean takeEarlyCancel(long id) {
        synchronized (earlyCancels) {
            return earlyCancels.remove(id);
        }
    }

    /**
     * With nothing in flight, collect so the heap shrinks to what the next build keeps. The
     * collection runs on a thread of its own: the command reader must never stop reading for it.
     */
    private void trim() {
        if (inflight.get() > 0) return;
        trimmer.execute(() -> {
            if (inflight.get() > 0) return;
            ZincJavaCompiler.forgetStamps();
            System.gc();
            send.accept(JsonFields.object()
                    .string(PluginProtocol.T, PluginProtocol.TRIMMED)
                    .number(PluginProtocol.USED_AFTER_GC, gauge.usedAfterGc())
                    .finish());
        });
    }

    /** Heap readings once a second and after each old collection, on a daemon thread. */
    private void startReporter() {
        Runnable report = () -> send.accept(gauge.frame(inflight.get()));
        gauge.onOldCollection(report);
        Thread t = new Thread(
                () -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        report.run();
                        try {
                            Thread.sleep(HEAP_EVERY_MILLIS);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                },
                "jk-javac-heap");
        t.setDaemon(true);
        t.start();
        reporter = t;
    }

    /** Let every compile in flight answer, then stop the readings. */
    private void drain() throws InterruptedException {
        ExecutorService p = pool;
        if (p != null) {
            p.shutdown();
            while (!p.awaitTermination(1, TimeUnit.MINUTES)) {
                // A compile can run for minutes; the engine stops a worker it no longer wants.
            }
        }
        trimmer.shutdownNow();
        Thread t = reporter;
        if (t != null) t.interrupt();
    }
}
