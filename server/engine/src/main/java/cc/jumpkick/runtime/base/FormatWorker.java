// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.engine.plugin.HeapLadder;
import cc.jumpkick.engine.plugin.HeapNotes;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.LearnedHeaps;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.WorkerContainment;
import cc.jumpkick.engine.plugin.WorkerFate;
import cc.jumpkick.engine.plugin.WorkerLeases;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * The {@code jk-formatter} worker fork: streams per-file results to a {@link FileObserver}, keeps
 * the run's tallies, and refuses to let the step succeed unless they {@linkplain #reconcile
 * reconcile} with the planned total. {@link FormatPlans} assembles the plan that calls in here.
 */
public final class FormatWorker {

    private FormatWorker() {}

    /**
     * Receives each file's result as the plugin streams it: {@code status} is {@code changed},
     * {@code clean}, {@code skipped}, or {@code error}.
     *
     * <p>{@link #SLOW} is the exception — a file still in flight, not a verdict. It carries no
     * progress and is not counted; it exists so a multi-minute peg on one file names that file
     * instead of going quiet.
     */
    public interface FileObserver {
        void onFile(String path, String status, @Nullable String message, int index, int total);
    }

    /** The per-file status that reports a file is <em>still</em> being formatted. */
    public static final String SLOW = "slow";

    /**
     * Whether a per-file result may be recorded in the mtime/size {@link FormatFreshnessIndex}.
     *
     * <p>That index is the <em>outer</em> filter: a recorded path is not sent to the worker at all
     * on the next run, so recording one is a claim that the file is finished. {@code error} was
     * always excluded.
     */
    static boolean recordsFreshness(String status, boolean check) {
        if ("error".equals(status) || SLOW.equals(status)) return false;
        // Under --check nothing was written, so a "changed" file's bytes are still the unformatted ones.
        return !check || !"changed".equals(status);
    }

    /** Summary counts, populated by the format step (all present once the plan finishes successfully). */
    public static final BuildPlanKey<Integer> CHANGED = BuildPlanKey.scalar("format-changed", Integer.class);

    public static final BuildPlanKey<Integer> CLEAN = BuildPlanKey.scalar("format-clean", Integer.class);
    public static final BuildPlanKey<Integer> ERRORS = BuildPlanKey.scalar("format-errors", Integer.class);
    public static final BuildPlanKey<Integer> TOTAL = BuildPlanKey.scalar("format-total", Integer.class);

    /**
     * The worker's exit code. On a plan that <em>succeeded</em> this is {@code 0} or {@code 1} and
     * nothing else ({@link #reconcile} fails the step on any other), so no caller can hand a user a
     * 139 from a SIGSEGV or a 137 from an OOM-kill.
     */
    public static final BuildPlanKey<Integer> WORKER_EXIT = BuildPlanKey.scalar("format-worker-exit", Integer.class);

    /** Files already clean by the mtime/size index — not sent to the worker. */
    static final BuildPlanKey<Integer> PRE_CLEAN = BuildPlanKey.scalar("format-preclean", Integer.class);

    /** The learned-heap module name of a format run: one row per project, whatever it formats. */
    static final String HEAP_MODULE = "format";

    /** The learned-heap key a format run of {@code projectDir} records into. */
    static HeapScope.Key heapKey(Path projectDir) {
        return new HeapScope.Key(
                projectDir.toAbsolutePath().normalize(),
                HEAP_MODULE,
                HeapScope.FORMAT,
                Runtime.version().feature());
    }

    /**
     * The heap a format fork starts at: this project's learned heap, else the first heap of a worker
     * jk has not seen, never below the memory plan's share. {@code null} when the user pinned worker
     * memory: their number is the heap, and it is neither learned nor retried.
     */
    static @Nullable Long startHeap(LearnedHeaps heaps, HeapScope.Key key) {
        if (!JvmOptions.autoHeapEnabled()) return null;
        HeapPlan.Plan plan = JvmOptions.processHeapPlan();
        long planned = plan != null ? plan.xmxBytes() : LearnedHeaps.FLOOR_BYTES;
        return heaps.choose(key.project(), key.module(), key.kind(), key.jdk(), planned);
    }

    /** The worker command line at a heap, or as planned when the heap is {@code null}. */
    @FunctionalInterface
    interface Launch {
        List<String> command(@Nullable Long heapBytes) throws IOException;
    }

    /**
     * {@code command} with its {@code -Xmx} set to {@code heapBytes}, recorded as jk's choice so the
     * budget may lower it and its peak is learned; unchanged when {@code heapBytes} is {@code null}.
     */
    static List<String> atHeap(List<String> command, @Nullable Long heapBytes) {
        if (heapBytes == null) return command;
        List<String> sized = WorkerLeases.rewriteHeap(command, heapBytes);
        JvmOptions.notePlannedCommand(sized);
        return sized;
    }

    /**
     * Fork the formatter worker, tally its per-file stream, publish the run's counts — and refuse to
     * let the step succeed unless the tally {@linkplain #reconcile reconciles} with {@code total}.
     *
     * <p>Every surface that says whether a format run worked reads {@code BuildPlanResult.success()}
     * — journal and dashboard via {@code FormatVerb}, terminal wedge via {@code FormatCommand}.
     *
     * <p>A worker that runs out of its own heap is forked again up the {@link HeapLadder}; one the
     * kernel killed for memory is forked once more at the same heap. A re-run reports every file
     * again, and only the files no earlier attempt reported are counted. The peak each attempt
     * reached is learned under {@code key}, so the next run starts at a heap that fits.
     *
     * @param heap the first heap, or {@code null} when the user pinned worker memory
     * @param preClean files the freshness index settled before the fork: part of {@code total}, so
     *     part of the sum
     * @param freshness index to record settled files in, or {@code null} when disabled; saved with
     *     whatever the worker did report even on a shortfall, so the next run retries the rest
     */
    static void runWorker(
            TaskContext ctx,
            Launch launch,
            @Nullable Long heap,
            HeapScope.Key key,
            LearnedHeaps heaps,
            int preClean,
            int total,
            boolean check,
            @Nullable FormatFreshnessIndex freshness,
            FileObserver observer)
            throws IOException, InterruptedException {
        Tally tally = new Tally(ctx, total, check, freshness, observer);
        Attempt last;
        String exhausted = null;
        if (heap == null) {
            last = tally.attempt(launch.command(null));
        } else {
            last = scoped(key, () -> tally.attempt(launch.command(heap)));
            WorkerFate.Cause cause = last.exit() == 0 || last.exit() == 1
                    ? WorkerFate.Cause.OTHER
                    : WorkerFate.classify(last.exit(), last.output());
            if (cause == WorkerFate.Cause.KILLED_FOR_MEMORY) {
                HeapNotes.note(HeapNotes.line(heap, heap, true));
                last = scoped(key, () -> tally.attempt(launch.command(heap)));
            } else if (cause == WorkerFate.Cause.HEAP_EXHAUSTED) {
                List<Long> ranOut = new ArrayList<>(List.of(heap));
                while (true) {
                    long failed = ranOut.getLast();
                    heaps.note(key, failed);
                    Long bigger = HeapLadder.next(ranOut);
                    if (bigger == null) {
                        exhausted = "format worker " + HeapLadder.ranOut(ranOut)
                                + "; raise it with [jvm] args = [\"-Xmx...\"]";
                        break;
                    }
                    HeapNotes.note(HeapNotes.line(bigger, failed, false));
                    last = scoped(key, () -> tally.attempt(launch.command(bigger)));
                    if (!WorkerFate.heapExhausted(last.exit(), last.output())) {
                        heaps.good(key, bigger);
                        break;
                    }
                    ranOut.add(bigger);
                }
            }
        }
        if (freshness != null) freshness.save();
        ctx.put(CHANGED, tally.changed.get());
        ctx.put(CLEAN, preClean + tally.clean.get());
        ctx.put(ERRORS, tally.errors.get());
        ctx.put(WORKER_EXIT, last.exit());
        int reported = preClean + tally.changed.get() + tally.clean.get() + tally.errors.get();
        String incomplete = exhausted != null ? exhausted : reconcile(reported, total, last.exit());
        if (incomplete != null) {
            ctx.error("format", incomplete);
            throw new IllegalStateException(incomplete);
        }
    }

    /** One fork's exit and the tail of what it printed besides the protocol. */
    private record Attempt(int exit, String output) {}

    /** The output kept per attempt: enough for the JVM's last words, not a whole run's chatter. */
    static final int OUTPUT_TAIL_CHARS = 16 * 1024;

    /**
     * The run's tallies across attempts. A path is counted the first time any attempt reports it,
     * so a re-run after a heap exhaustion does not count a file twice.
     */
    private static final class Tally {
        final TaskContext ctx;
        final int total;
        final boolean check;
        final @Nullable FormatFreshnessIndex freshness;
        final FileObserver observer;
        final AtomicInteger changed = new AtomicInteger();
        final AtomicInteger clean = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicInteger index = new AtomicInteger();
        final Set<String> reported = ConcurrentHashMap.newKeySet();

        Tally(
                TaskContext ctx,
                int total,
                boolean check,
                @Nullable FormatFreshnessIndex freshness,
                FileObserver observer) {
            this.ctx = ctx;
            this.total = total;
            this.check = check;
            this.freshness = freshness;
            this.observer = observer;
        }

        Attempt attempt(List<String> command) throws IOException, InterruptedException {
            StringBuilder tail = new StringBuilder();
            int exit = new PluginClient("##JKFMT:")
                    .on("file", this::file)
                    .passthrough(line -> {
                        ctx.output(line);
                        synchronized (tail) {
                            tail.append(line).append('\n');
                            if (tail.length() > OUTPUT_TAIL_CHARS) tail.delete(0, tail.length() - OUTPUT_TAIL_CHARS);
                        }
                    })
                    .run(command);
            synchronized (tail) {
                return new Attempt(exit, tail.toString());
            }
        }

        private void file(String json) {
            String status = Jsonl.requiredStr(json, "status");
            String path = Jsonl.requiredStr(json, "path");
            if (SLOW.equals(status)) {
                // Live chatter about a file the run has not settled: it keeps its place in the
                // stream (the CLI names it) but touches no tally, no freshness record, and no
                // progress — its verdict is still to come.
                observer.onFile(path, status, Jsonl.str(json, "msg"), index.get(), total);
                return;
            }
            if (!reported.add(path)) return;
            if ("changed".equals(status)) {
                changed.incrementAndGet();
            } else if ("error".equals(status)) {
                errors.incrementAndGet();
            } else {
                clean.incrementAndGet();
            }
            if (freshness != null && recordsFreshness(status, check)) {
                freshness.record(Path.of(path));
            }
            observer.onFile(path, status, Jsonl.str(json, "msg"), index.incrementAndGet(), total);
            ctx.progress(1);
        }
    }

    private static Attempt scoped(HeapScope.Key key, AttemptBody body) throws IOException, InterruptedException {
        try {
            return HeapScope.call(key, body::run);
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @FunctionalInterface
    private interface AttemptBody {
        Attempt run() throws Exception;
    }

    /**
     * The completeness verdict for one format run: {@code null} when the run adds up, else the
     * diagnostic that fails the step. Silence cannot be seen in a status — a dead worker reports
     * nothing, and nothing is what a clean file reports too — only in a count against a total.
     *
     * <p><b>The count.</b> {@code reported} is every file the worker spoke about plus the ones the
     * freshness index settled before the fork; {@code total} is every file the run set out to visit.
     * A shortfall means files were never looked at. A non-zero exit is <em>not</em> the trigger:
     * {@code 1} is the worker's legitimate {@code --check} drift code ({@code CodeFormatter}), and
     * failing on it would call every drifted tree a crash.
     *
     * <p><b>The exit vocabulary.</b> The worker's exit law is {@code 0} or {@code 1} and nothing
     * else, so any other value is a death. This arm catches what the count cannot: a crash
     * <em>after</em> the last file event, tallies balanced. Without it the wedge prints green while
     * the CLI hands the shell a 139 — the same contradiction, one file later.
     */
    static @Nullable String reconcile(int reported, int total, int exit) {
        if (reported < total) {
            return "format worker reported on " + reported + " of " + total + " files — " + (total - reported)
                    + " were never visited (worker exit " + exit
                    + "); nothing was recorded for them, so the next `jk format` retries them";
        }
        if (reported > total) {
            return "format worker reported on " + reported + " files but only " + total + " were planned — "
                    + (reported - total) + " more results than files (worker exit " + exit + ")";
        }
        if (exit != 0 && exit != 1) {
            if (WorkerContainment.killedForMemory(exit)) {
                return "format worker killed for memory after reporting on all " + total + " files";
            }
            return "format worker exited " + exit + " after reporting on all " + total
                    + " files; its exit law is 0 or 1, so " + exit + " is a crash, not a verdict";
        }
        return null;
    }
}
