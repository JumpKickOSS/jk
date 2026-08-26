// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
     */
    public interface FileObserver {
        void onFile(String path, String status, String message, int index, int total);
    }

    /**
     * Whether a per-file result may be recorded in the mtime/size {@link FormatFreshnessIndex}.
     *
     * <p>That index is the <em>outer</em> filter: a recorded path is not sent to the worker at all
     * on the next run, so recording one is a claim that the file is finished. {@code error} was
     * always excluded.
     */
    static boolean recordsFreshness(String status, boolean check) {
        if ("error".equals(status)) return false;
        // Under --check nothing was written, so a "changed" file's bytes are still the unformatted ones.
        return !check || !"changed".equals(status);
    }

    /** Summary counts, populated by the format step (all present once the plan finishes successfully). */
    public static final BuildPlanKey<Integer> CHANGED = BuildPlanKey.of("format-changed", Integer.class);

    public static final BuildPlanKey<Integer> CLEAN = BuildPlanKey.of("format-clean", Integer.class);
    public static final BuildPlanKey<Integer> ERRORS = BuildPlanKey.of("format-errors", Integer.class);
    public static final BuildPlanKey<Integer> TOTAL = BuildPlanKey.of("format-total", Integer.class);

    /**
     * The worker's exit code. On a plan that <em>succeeded</em> this is {@code 0} or {@code 1} and
     * nothing else ({@link #reconcile} fails the step on any other), so no caller can hand a user a
     * 139 from a SIGSEGV or a 137 from an OOM-kill.
     */
    public static final BuildPlanKey<Integer> WORKER_EXIT = BuildPlanKey.of("format-worker-exit", Integer.class);

    /** Files already clean by the mtime/size index — not sent to the worker. */
    static final BuildPlanKey<Integer> PRE_CLEAN = BuildPlanKey.of("format-preclean", Integer.class);

    /**
     * Fork the formatter worker, tally its per-file stream, publish the run's counts — and refuse to
     * let the step succeed unless the tally {@linkplain #reconcile reconciles} with {@code total}.
     *
     * <p>Every surface that says whether a format run worked reads {@code BuildPlanResult.success()}
     * — journal and dashboard via {@code FormatVerb}, terminal wedge via {@code FormatCommand}.
     * Before this check the step could not fail at all, so a worker that died at file 500 of 2,063
     * journaled as a successful, complete format with nothing changed and no errors, while the exit
     * code the CLI returned one line later said 139. Two readers of one fact, disagreeing.
     *
     * @param command the worker command line ({@code PluginLaunch.javaCommand})
     * @param preClean files the freshness index settled before the fork: part of {@code total}, so
     *     part of the sum
     * @param freshness index to record settled files in, or {@code null} when disabled; saved with
     *     whatever the worker did report even on a shortfall, so the next run retries the rest
     */
    static void runWorker(
            TaskContext ctx,
            List<String> command,
            int preClean,
            int total,
            boolean check,
            FormatFreshnessIndex freshness,
            FileObserver observer)
            throws IOException, InterruptedException {
        AtomicInteger changed = new AtomicInteger();
        AtomicInteger clean = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();
        int exit = new PluginClient("##JKFMT:")
                .on("file", json -> {
                    String status = Jsonl.str(json, "status");
                    String path = Jsonl.str(json, "path");
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
                })
                .passthrough(ctx::output)
                .run(command);
        if (freshness != null) freshness.save();
        ctx.put(CHANGED, changed.get());
        ctx.put(CLEAN, preClean + clean.get());
        ctx.put(ERRORS, errors.get());
        ctx.put(WORKER_EXIT, exit);
        int reported = preClean + changed.get() + clean.get() + errors.get();
        String incomplete = reconcile(reported, total, exit);
        if (incomplete != null) {
            ctx.error("format", incomplete);
            throw new IllegalStateException(incomplete);
        }
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
    static String reconcile(int reported, int total, int exit) {
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
            return "format worker exited " + exit + " after reporting on all " + total
                    + " files; its exit law is 0 or 1, so " + exit + " is a crash, not a verdict";
        }
        return null;
    }
}
