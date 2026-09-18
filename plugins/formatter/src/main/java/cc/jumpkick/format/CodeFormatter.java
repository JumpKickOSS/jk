// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.HostLoad;
import cc.jumpkick.host.HostProcessors;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import com.diffplug.spotless.DirtyState;
import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.LineEnding;
import com.diffplug.spotless.Provisioner;
import com.diffplug.spotless.groovy.RemoveSemicolonsStep;
import com.diffplug.spotless.java.GoogleJavaFormatStep;
import com.diffplug.spotless.java.ImportOrderStep;
import com.diffplug.spotless.java.PalantirJavaFormatStep;
import com.diffplug.spotless.java.RemoveUnusedImportsStep;
import com.diffplug.spotless.kotlin.KtfmtStep;
import com.diffplug.spotless.scala.ScalaFmtStep;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk-formatter} plugin: optional FQCN-shorten pass, then Spotless. Host forks with a
 * tab-delimited spec file; emits {@code ##JKFMT:} JSONL per file + summary.
 *
 * <p>Java Spotless pipeline: {@code importOrder} → {@code removeUnusedImports} → Palantir / Google
 * / AOSP. Kotlin is ktfmt. Groovy is semicolon removal. Scala is scalafmt. FQCN shortening is a
 * first-party index pass (no compiler) that runs before Spotless.
 *
 * <p>Every file runs under a wall bound ({@link FormatWatchdog}): one pathological source cannot
 * dominate the run, and a file that is merely slow is named while it is still in flight.
 */
public final class CodeFormatter implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-formatter", "##JKFMT:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        if (args.isEmpty()) {
            System.err.println("jk-formatter: expected spec file path");
            return Exit.USAGE;
        }
        Spec spec = Spec.from(PluginSpec.read(Path.of(args.get(0))));

        FormatStampCache stampCache = spec.cacheDir != null && spec.configKey != null
                ? new FormatStampCache(CacheTree.FORMAT_STAMPS.under(spec.cacheDir), spec.configKey)
                : null;

        // Built once for the whole run, then only read, so every thread shares one copy.
        TypeIndex index = spec.optimizeImports ? TypeIndex.scan(spec.indexFiles) : null;

        Workers workers = new Workers(spec);
        Tally tally;
        try {
            tally = formatAll(spec, out, stampCache, (ref, i, dog) -> {
                // Untimed on purpose: the first file a thread sees pays for its Spotless step chain
                // and the classloaders behind it, which is not that file's own cost.
                Formatter fmt = workers.get().formatter(ref.kind());
                // The host listed a file but sent no jars for its language. Reported, not skipped in
                // silence: a file the run was told to visit and said nothing about is
                // indistinguishable from a dead worker, which is the shortfall
                // FormatWorker.reconcile exists to catch.
                if (fmt == null) {
                    return new FileResult(ref.file(), "error", "no " + ref.kind() + " formatter jars were provided");
                }
                try (var window = dog.watch(i, ref.file())) {
                    return formatOne(ref, fmt, spec, stampCache, index);
                }
            });
        } finally {
            workers.close();
            // One write for the whole run: every contains/record above was a map operation.
            if (stampCache != null) stampCache.save();
        }

        return tally.errors() > 0 || (!spec.apply && tally.changed() > 0) ? 1 : 0;
    }

    /**
     * One file's verdict, decided off-thread and emitted in spec order by {@link #formatAll}. What the
     * task would have done to the world rides along instead of being done: {@code bytes} to write
     * to the file and a {@code stamp} to record, both null when there is nothing to do. The run
     * {@linkplain #commit applies} them only after the verdict is confirmed to be the file's own.
     */
    record FileResult(
            File file,
            String status,
            @Nullable String msg,
            byte @Nullable [] bytes,
            @Nullable String stamp) {

        FileResult(File file, String status, @Nullable String msg) {
            this(file, status, msg, null, null);
        }
    }

    /** How the run's files fell out, counted as they were emitted. */
    record Tally(int changed, int clean, int errors) {}

    /**
     * One file's work as the run's pool sees it. The seam is the wall bound: open a
     * {@link FormatWatchdog#watch window} around the part that formats, and do any per-thread setup
     * before it.
     */
    interface FileWork {
        FileResult apply(FileRef ref, int index, FormatWatchdog dog);
    }

    /**
     * Format every file in the spec in parallel, emitting per-file results in spec order.
     *
     * <p>Spec order is what keeps the stream — and every tally the host derives from it —
     * independent of thread timing. It is also why the wall bound matters here rather than in the
     * work: a file that never returns stalls the emission of every file behind it, so
     * {@link FormatWatchdog} has to be able to settle one on the run's behalf.
     *
     * <p>A settled timeout leaves its thread very possibly still running, since nothing can stop a
     * line-break search that never checks for an interrupt. The run therefore adds a replacement
     * thread per abandoned one (up to as many as it started with) so the remaining files keep the
     * concurrency they were planned for.
     *
     * <p>A task decides but does not act: the bytes it would write and the key it would stamp ride
     * in its {@link FileResult} and are {@linkplain #commit applied} here, on this thread, once the
     * verdict is known to be the file's own. A file the run gave up on is therefore never written
     * or stamped, however late its formatter comes back.
     */
    static Tally formatAll(Spec spec, ProtocolWriter out, @Nullable FormatStampCache memo, FileWork work) {
        int slots = concurrency(spec);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                slots, slots, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), CodeFormatter::formatThread);
        AtomicInteger abandoned = new AtomicInteger();
        FormatWatchdog dog = new FormatWatchdog(
                spec.fileWarnMs,
                new FormatTimeout(spec.fileTimeoutMs, spec.fileTimeoutWhy),
                Clock.SYSTEM,
                (file, elapsedMs) ->
                        emitFile(out, file, "slow", "still formatting after " + FormatWatchdog.human(elapsedMs)),
                () -> replaceSlot(pool, slots, abandoned));
        Settling settling = new Settling(
                dog, abandoned, slots, spec.fileTimeoutMs, memo, new AtomicIntegerArray(spec.files.size()));
        int changed = 0, clean = 0, errors = 0;
        try {
            List<Future<FileResult>> pending = new ArrayList<>(spec.files.size());
            dog.start();
            try {
                for (int i = 0; i < spec.files.size(); i++) {
                    FileRef ref = spec.files.get(i);
                    int index = i;
                    // A task that loses the claim was forfeited by settle; its result is nobody's.
                    pending.add(pool.submit(() -> settling.claim(index) ? work.apply(ref, index, dog) : null));
                }
            } finally {
                pool.shutdown();
            }
            for (int i = 0; i < pending.size(); i++) {
                FileResult result = commit(settle(pending.get(i), i, spec.files.get(i), settling), memo);
                switch (result.status()) {
                    case "changed" -> changed++;
                    case "error" -> errors++;
                    default -> clean++;
                }
                emitFile(out, result.file(), result.status(), result.msg());
            }
        } finally {
            dog.close();
            pool.shutdownNow();
        }
        return new Tally(changed, clean, errors);
    }

    /** How long {@link #settle} waits between asking the watchdog whether a file is still the run's. */
    private static final long SETTLE_POLL_MS = 200;

    /**
     * The verdict for one file: the task's own, or the run's when the task will not produce one.
     * Every arm names the file — {@code pending} is index-aligned with {@code spec.files}, so a task
     * that crashed or was abandoned is still knowable, and reconcile never blames the wrong
     * "never visited" file.
     */
    private static FileResult settle(Future<FileResult> f, int index, FileRef ref, Settling s) {
        while (true) {
            // Before blocking: once the run is out of threads, every file still queued is settled
            // now rather than after a poll each.
            if (s.forfeits(index)) return unstarted(ref);
            try {
                FileResult result = f.get(SETTLE_POLL_MS, TimeUnit.MILLISECONDS);
                // Coming back late does not un-abandon a file. The run already acted on the verdict
                // — replaced the slot, named the file — and a result that depends on whether a task
                // beat a poll by a few milliseconds is not one anybody can reason about.
                String verdict = s.dog().verdict(index);
                return verdict == null ? result : timedOut(ref, verdict, s);
            } catch (TimeoutException e) {
                String verdict = s.dog().verdict(index);
                if (verdict != null) return timedOut(ref, verdict, s);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                return new FileResult(ref.file(), "error", String.valueOf(cause.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new FileResult(ref.file(), "error", "the format run was interrupted");
            }
        }
    }

    /**
     * What settling one file needs beyond the file itself. {@code claims} has one slot per spec
     * index, taken exactly once: by the task as it begins, or by {@link #forfeits} when the run has
     * no thread left to start it on.
     */
    private record Settling(
            FormatWatchdog dog,
            AtomicInteger abandoned,
            int slots,
            long limitMs,
            @Nullable FormatStampCache memo,
            AtomicIntegerArray claims) {

        /** The task's claim on its file. False when the run already forfeited it. */
        boolean claim(int index) {
            return claims.compareAndSet(index, 0, 1);
        }

        /**
         * Whether the run is out of threads and nothing has begun on the file at {@code index}. True
         * takes the claim, so a task that turns up afterwards finds the file is no longer its own.
         */
        boolean forfeits(int index) {
            return abandoned.get() >= tolerable(slots) && claims.compareAndSet(index, 0, 1);
        }
    }

    /**
     * Do what a settled task decided: write its bytes and record its stamp. Runs on the run's own
     * thread, after {@link #settle} has confirmed the verdict is the file's — the results the run
     * writes on a file's behalf carry nothing to do, so a file it gave up on is never written or
     * stamped. A write that fails is that file's error.
     */
    static FileResult commit(FileResult r, @Nullable FormatStampCache memo) {
        if (r.bytes() != null) {
            try {
                Files.write(r.file().toPath(), r.bytes());
            } catch (IOException e) {
                return new FileResult(r.file(), "error", e.getMessage());
            }
        }
        if (memo != null) memo.record(r.stamp());
        return r;
    }

    /**
     * Report a file the run gave up on, and — when its shape accounts for that — remember it so the
     * next run does not spend the limit reaching the same verdict.
     *
     * <p>Only a shape that {@linkplain SourceShape.Shape#explainsAStall explains the stall} is
     * remembered. A file of ordinary shape that blew the limit is far more likely a host that
     * stalled than a source nothing can format, and a memo on it would refuse a good file on every
     * later run until somebody edited it — worse than paying the limit again.
     *
     * <p>The memo is keyed on the bytes on disk, which are the bytes the next run will read: a task
     * writes nothing until the run confirms its verdict, so the file is as it was. One read serves
     * the key, the decision and the message.
     */
    private static FileResult timedOut(FileRef ref, String verdict, Settling s) {
        byte[] bytes = readOrNull(ref.file());
        if (bytes == null) return new FileResult(ref.file(), "error", verdict);
        SourceShape.Shape shape = SourceShape.of(text(bytes));
        if (s.memo() != null && shape.explainsAStall()) {
            s.memo().recordTimeout(s.memo().keyFor(bytes), s.limitMs());
        }
        String note = SourceShape.phrase(shape);
        return new FileResult(ref.file(), "error", verdict + (note.isEmpty() ? UNEXPLAINED : note));
    }

    /**
     * What a timeout the source's shape does not account for says instead.
     *
     * <p>That file is deliberately not remembered, so it will be attempted again — and the user is
     * the only one who can tell a genuinely enormous source from a host that was busy. Both are
     * answered by the same knob, and a bare "timed out" answers neither.
     */
    private static final String UNEXPLAINED = "; nothing about this file's shape explains that, so it was not"
            + " remembered — raise jk.format.file-timeout-ms if the file is simply very large, or this host slow";

    /**
     * Whether a remembered timeout still answers for this run.
     *
     * <p>A limit that has been <em>raised</em> since is a request to try the file again, and a run
     * with the bound off ({@code 0}) is a request to let it take as long as it likes. A limit that is
     * the same or tighter would only reach the same verdict, later — which is the whole cost the memo
     * exists to stop paying.
     */
    static boolean remembersTimeout(long recordedLimitMs, long limitMs) {
        return recordedLimitMs > 0 && limitMs > 0 && recordedLimitMs >= limitMs;
    }

    /**
     * The message for a file the run declined to attempt. It reports the limit that was actually
     * spent, on the run that spent it — claiming this run's elapsed time would be a duration nobody
     * waited for.
     */
    private static String rememberedTimeout(long recordedLimitMs, byte[] source) {
        return "timed out at a " + recordedLimitMs + " ms limit on an earlier run and has not changed"
                + " since, so it was not retried" + SourceShape.postMortem(text(source));
    }

    private static byte @Nullable [] readOrNull(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * The run has spent its replacement threads, so nothing is going to start this file. Reached
     * only when {@link Settling#forfeits} took the file's claim, which a task that had begun on it
     * would already hold — so this is a fact about the file, not a guess, and it cannot race a
     * result the file was about to produce.
     */
    private static FileResult unstarted(FileRef ref) {
        return new FileResult(
                ref.file(),
                "error",
                "not formatted: every formatter thread the run could spare was left wedged by a file that timed out");
    }

    /**
     * Replace the thread a timed-out file kept. Nothing can stop a formatter that never checks for
     * an interrupt, so that thread no longer counts as a slot.
     *
     * <p>One replacement per slot the run started with. Past that the pool would grow a live
     * formatter per pathological file inside a heap sized for one worker, so the run stops promising
     * the files behind them a thread and {@linkplain #unstarted settles them with a reason} instead.
     */
    private static void replaceSlot(ThreadPoolExecutor pool, int slots, AtomicInteger abandoned) {
        int lost = abandoned.incrementAndGet();
        if (lost > slots) return;
        pool.setMaximumPoolSize(slots + lost);
        pool.setCorePoolSize(slots + lost);
    }

    /** How many files a run will lose to the timeout before it stops replacing their threads. */
    private static int tolerable(int slots) {
        return slots * 2;
    }

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    /**
     * A pool thread. Daemon because a file that outlasted its timeout may hold this thread forever:
     * the worker's exit must not wait on it.
     */
    private static Thread formatThread(Runnable r) {
        Thread t = new Thread(r, "jk-format-" + THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    }

    /**
     * How many files to format at once. The work is per-file independent and CPU-bound, and the host
     * launches this worker as its only fork, so {@code ActiveProcessorCount} is the whole machine.
     * Capped at 8: past that the curve flattens and every thread adds a live Spotless step chain to a
     * heap sized for one worker. {@link Spec#threads} overrides for measurement.
     */
    static int concurrency(Spec spec) {
        int files = Math.max(1, spec.files.size());
        if (spec.threads > 0) return Math.min(spec.threads, files);
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        int byHeap = (int) Math.max(1, heapMb / 256);
        return Math.max(1, Math.min(Math.min(cores, 8), Math.min(byHeap, files)));
    }

    /**
     * Per-thread Spotless formatters. A {@link Formatter} owns its step chain — google-java-format,
     * palantir, ktfmt and scalafmt behind their own classloaders — and is not documented thread-safe,
     * so no two threads share one. Built lazily per language: a tree with no Scala never pays for a
     * scalafmt chain, and a thread that only ever sees Java builds only that one.
     *
     * <p>The {@link TypeIndex} is deliberately <em>not</em> in here — it is immutable once built, so
     * one copy serves every thread.
     */
    private static final class Workers implements AutoCloseable {

        private final List<Holder> created = Collections.synchronizedList(new ArrayList<>());
        private final ThreadLocal<Holder> local;

        Workers(Spec spec) {
            this.local = ThreadLocal.withInitial(() -> {
                Holder h = new Holder(spec);
                created.add(h);
                return h;
            });
        }

        Holder get() {
            return local.get();
        }

        @Override
        public void close() {
            synchronized (created) {
                for (Holder h : created) h.close();
            }
        }

        static final class Holder implements AutoCloseable {
            private final Spec spec;
            private final EnumMap<Kind, @Nullable Formatter> byKind = new EnumMap<>(Kind.class);

            Holder(Spec spec) {
                this.spec = spec;
            }

            /** The formatter for {@code kind} on this thread, or null when the run has no jars for it. */
            @Nullable
            Formatter formatter(Kind kind) {
                if (byKind.containsKey(kind)) return byKind.get(kind);
                Formatter f = build(kind);
                byKind.put(kind, f);
                return f;
            }

            private @Nullable Formatter build(Kind kind) {
                List<FormatterStep> steps =
                        switch (kind) {
                            case JAVA -> spec.javaJars.isEmpty() ? null : javaSteps(spec);
                            case KOTLIN ->
                                spec.kotlinJars.isEmpty()
                                        ? null
                                        : List.of(KtfmtStep.create(
                                                spec.kotlinVersion,
                                                provisioner(spec.kotlinJars),
                                                ktfmtStyle(spec.kotlinStyle),
                                                ktfmtOptions(spec.kotlinMaxWidth)));
                            case GROOVY -> groovySteps();
                            case SCALA -> spec.scalaJars.isEmpty() ? null : scalaSteps(spec);
                        };
                if (steps == null) return null;
                Formatter f = Formatter.builder()
                        .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                        .encoding(StandardCharsets.UTF_8)
                        .steps(steps)
                        .build();
                warm(f, kind);
                return f;
            }

            /**
             * Format a stub so the chain's lazy state — a provisioned classloader per jar set, the
             * formatter object behind it — exists before any real file is measured against it. Without
             * this the first file a thread sees carries that cost and can look pathological to the
             * per-file timeout. Best-effort: a chain that cannot format a stub will say so on its
             * first real file, like any other failure.
             */
            private static void warm(Formatter f, Kind kind) {
                String stub =
                        switch (kind) {
                            case JAVA, GROOVY -> "class A {}\n";
                            case KOTLIN, SCALA -> "class A\n";
                        };
                try {
                    DirtyState.of(f, new File("A" + extension(kind)), stub.getBytes(StandardCharsets.UTF_8));
                } catch (RuntimeException ignored) {
                    // Warming is an optimization, never a requirement.
                }
            }

            private static String extension(Kind kind) {
                return switch (kind) {
                    case JAVA -> ".java";
                    case KOTLIN -> ".kt";
                    case GROOVY -> ".groovy";
                    case SCALA -> ".scala";
                };
            }

            @Override
            public void close() {
                for (Formatter f : byKind.values()) {
                    if (f == null) continue;
                    try {
                        f.close();
                    } catch (Throwable ignored) {
                        // A thread abandoned to the per-file timeout may still be inside this
                        // formatter. Its step chain dies with the process either way, and a close
                        // that trips over the live use must not turn a reported timeout into a
                        // worker crash — reconcile reads any exit outside {0, 1} as a death.
                    }
                }
            }
        }
    }

    /**
     * One file, start to finish: stamp lookup, the FQCN pass, then Spotless, all in memory off one
     * read. Runs on a pool thread under the run's wall bound. It writes nothing and stamps nothing:
     * the verdict, the bytes and the key come back in the result for {@link #formatAll} to emit in
     * spec order and {@linkplain #commit apply}.
     */
    static FileResult formatOne(
            FileRef ref, Formatter fmt, Spec spec, @Nullable FormatStampCache stampCache, @Nullable TypeIndex index) {
        try {
            byte[] original = Files.readAllBytes(ref.file().toPath());
            String stampKey = stampCache != null ? stampCache.keyFor(original) : null;
            if (stampCache != null && stampKey != null && stampCache.contains(stampKey)) {
                return new FileResult(ref.file(), "clean", null);
            }

            long timedOutAt = stampCache != null && stampKey != null ? stampCache.timedOutAt(stampKey) : 0;
            if (remembersTimeout(timedOutAt, spec.fileTimeoutMs)) {
                return new FileResult(ref.file(), "error", rememberedTimeout(timedOutAt, original));
            }

            if (ref.kind() == Kind.JAVA && isUnnamedClass(original)) {
                return new FileResult(ref.file(), "skipped", null, null, stampKey);
            }

            // Java only. The blanking pass implements Java's lexeme set, and the other three differ in
            // ways that make it rewrite string contents: Groovy has `'''` blocks and slashy `/…/`
            // literals, Kotlin and Scala have `import … as Alias` and brace/underscore imports whose
            // bindings this pass cannot read. Java is also the only one of the four whose Spotless
            // chain includes an import-repair step, so it is the only one where a mistake here would
            // be noticed downstream rather than written and stamped. Widening this needs a real lexer
            // per language, not a wider regex.
            byte[] input = original;
            if (index != null && ref.kind() == Kind.JAVA) {
                FqcnShortener.Result r = FqcnShortener.shorten(text(original), index, syntax(ref.kind()));
                if (r.changed()) input = r.source().getBytes(StandardCharsets.UTF_8);
            }

            DirtyState state = DirtyState.of(fmt, ref.file(), input);
            if (state.didNotConverge()) {
                return new FileResult(ref.file(), "error", "formatter did not converge");
            }
            byte[] formatted = state.isClean() ? input : canonical(state);
            if (Arrays.equals(formatted, original)) {
                return new FileResult(ref.file(), "clean", null, null, stampKey);
            }
            if (!spec.apply) return new FileResult(ref.file(), "changed", null);
            String formattedKey = stampCache != null ? stampCache.keyFor(formatted) : null;
            return new FileResult(ref.file(), "changed", null, formatted, formattedKey);
        } catch (Exception e) {
            return new FileResult(ref.file(), "error", e.getMessage());
        }
    }

    private static byte[] canonical(DirtyState state) throws IOException {
        var out = new ByteArrayOutputStream();
        state.writeCanonicalTo(out);
        return out.toByteArray();
    }

    private static FqcnShortener.Syntax syntax(Kind kind) {
        return switch (kind) {
            case JAVA -> FqcnShortener.Syntax.JAVA;
            case KOTLIN -> FqcnShortener.Syntax.KOTLIN;
            case GROOVY -> FqcnShortener.Syntax.GROOVY;
            case SCALA -> FqcnShortener.Syntax.SCALA;
        };
    }

    private static void emitFile(ProtocolWriter out, File file, String status, @Nullable String msg) {
        out.emit(PluginReply.file(file.getAbsolutePath(), status, msg));
    }

    /**
     * Java steps in Spotless order: optional import order, optional remove-unused, then the chosen
     * style formatter. {@code removeUnusedImports} uses google-java-format under the hood, so when
     * the style is Palantir the host also passes GJF jars in {@link Spec#removeUnusedJars}.
     */
    static List<FormatterStep> javaSteps(Spec spec) {
        Provisioner styleProv = provisioner(spec.javaJars);
        var steps = new ArrayList<FormatterStep>();
        if (spec.importOrder) {
            steps.add(ImportOrderStep.forJava().createFrom());
        }
        if (spec.removeUnusedImports) {
            Set<File> removeUnusedJars = spec.removeUnusedJars.isEmpty() ? spec.javaJars : spec.removeUnusedJars;
            steps.add(RemoveUnusedImportsStep.create(provisioner(removeUnusedJars)));
        }
        steps.add(styleStep(spec, styleProv));
        return List.copyOf(steps);
    }

    static List<FormatterStep> groovySteps() {
        return List.of(RemoveSemicolonsStep.create());
    }

    static List<FormatterStep> scalaSteps(Spec spec) {
        File config = scalaConfig(spec);
        return List.of(ScalaFmtStep.create(spec.scalaVersion, provisioner(spec.scalaJars), config));
    }

    /** Palantir-aligned defaults: 4-space indent, 120 columns, Scala 3 dialect. */
    private static @Nullable File scalaConfig(Spec spec) {
        Path dir = spec.cacheDir;
        if (dir == null) return null;
        try {
            Files.createDirectories(dir);
            Path conf = dir.resolve("jk-scalafmt.conf");
            Files.writeString(conf, """
                    version = "%s"
                    runner.dialect = scala3
                    maxColumn = 120
                    indent.main = 4
                    """.formatted(spec.scalaVersion), StandardCharsets.UTF_8);
            return conf.toFile();
        } catch (IOException e) {
            return null;
        }
    }

    private static FormatterStep styleStep(Spec spec, Provisioner prov) {
        if ("palantir".equalsIgnoreCase(spec.javaStyle)) {
            return PalantirJavaFormatStep.create(spec.javaVersion, "PALANTIR", /* formatJavadoc */ false, prov);
        }
        return GoogleJavaFormatStep.create(spec.javaVersion, spec.javaStyle.toUpperCase(Locale.ROOT), prov);
    }

    private static Provisioner provisioner(Set<File> jars) {
        return (withTransitives, coords) -> jars;
    }

    private static KtfmtStep.Style ktfmtStyle(String style) {
        return switch (style.toLowerCase(Locale.ROOT)) {
            case "kotlinlang" -> KtfmtStep.Style.KOTLINLANG;
            case "google" -> KtfmtStep.Style.GOOGLE;
            case "meta" -> KtfmtStep.Style.META;
            default -> throw new IllegalArgumentException("unknown kotlin style: " + style);
        };
    }

    private static KtfmtStep.KtfmtFormattingOptions ktfmtOptions(int maxWidth) {
        var opts = new KtfmtStep.KtfmtFormattingOptions();
        if (maxWidth > 0) opts.setMaxWidth(maxWidth);
        return opts;
    }

    private static final Pattern TYPE_DECL =
            Pattern.compile("\\b(class|interface|enum|record)\\s+\\w|@interface\\s+\\w");

    /** True if the source has no top-level type declaration (Java 21+ unnamed class). */
    static boolean isUnnamedClass(byte[] bytes) {
        String src = new String(bytes, StandardCharsets.UTF_8);
        String stripped = src.replaceAll("//[^\n]*", "").replaceAll("(?s)/\\*.*?\\*/", " ");
        return !TYPE_DECL.matcher(stripped).find();
    }

    enum Kind {
        JAVA,
        KOTLIN,
        GROOVY,
        SCALA
    }

    record FileRef(Kind kind, File file) {}

    static final class Spec {
        boolean apply = true;
        String javaStyle = "palantir";
        String javaVersion = PalantirJavaFormatStep.defaultVersion();
        Set<File> javaJars = new LinkedHashSet<>();
        Set<File> removeUnusedJars = new LinkedHashSet<>();

        String kotlinStyle = "kotlinlang";
        String kotlinVersion = KtfmtStep.defaultVersion();
        int kotlinMaxWidth = 0;
        Set<File> kotlinJars = new LinkedHashSet<>();
        String scalaVersion = ScalaFmtStep.defaultVersion();
        Set<File> scalaJars = new LinkedHashSet<>();
        boolean optimizeImports = false;
        boolean importOrder = true;
        boolean removeUnusedImports = true;

        @Nullable
        Path cacheDir = null;

        @Nullable
        String configKey = null;
        /** All project sources the type index should read (may be a superset of {@link #files}). */
        List<Path> indexFiles = List.of();

        /** How long one file may be in flight before the run names it. 0 or less disables the notice. */
        long fileWarnMs = FormatWatchdog.DEFAULT_WARN_MS;

        /** How long one file may be in flight before the run gives up on it. 0 or less disables the bound. */
        long fileTimeoutMs = FormatWatchdog.DEFAULT_TIMEOUT_MS;

        /** Why {@link #fileTimeoutMs} differs from the default — the host's load — or {@code ""}; see {@link FormatTimeout}. */
        String fileTimeoutWhy = "";

        /** Files to format at once; 0 lets {@link #concurrency} size the run to the machine. */
        int threads = 0;

        final List<FileRef> files = new ArrayList<>();

        static Spec from(PluginSpec ws) {
            Spec s = new Spec();
            PluginConfig c = ws.config();
            s.apply = c.bool("apply", true);
            s.javaStyle = c.stringOpt("javaStyle").orElse(s.javaStyle);
            s.javaVersion = c.stringOpt("javaVersion").orElse(s.javaVersion);
            s.javaJars = jars(c.stringList("javaJars"));
            s.removeUnusedJars = jars(c.stringList("removeUnusedJars"));
            s.kotlinStyle = c.stringOpt("kotlinStyle").orElse(s.kotlinStyle);
            s.kotlinVersion = c.stringOpt("kotlinVersion").orElse(s.kotlinVersion);
            s.kotlinMaxWidth = (int) c.intValue("kotlinMaxWidth", 0);
            s.kotlinJars = jars(c.stringList("kotlinJars"));
            s.scalaVersion = c.stringOpt("scalaVersion").orElse(s.scalaVersion);
            s.scalaJars = jars(c.stringList("scalaJars"));
            s.optimizeImports = c.bool("optimizeImports", false);
            s.importOrder = c.bool("importOrder", true);
            s.removeUnusedImports = c.bool("removeUnusedImports", true);
            s.fileWarnMs = longProperty("jk.format.file-warn-ms", s.fileWarnMs);
            FormatTimeout timeout = hasProperty("jk.format.file-timeout-ms")
                    ? FormatTimeout.explicit(longProperty("jk.format.file-timeout-ms", s.fileTimeoutMs))
                    : FormatTimeout.forHost(HostLoad.loadAverage(), HostProcessors.count());
            s.fileTimeoutMs = timeout.ms();
            s.fileTimeoutWhy = timeout.why();
            s.threads = (int) longProperty("jk.format.threads", s.threads);
            c.stringOpt("cacheDir").ifPresent(p -> s.cacheDir = Path.of(p));
            c.stringOpt("configKey").ifPresent(k -> s.configKey = k);
            List<Path> index = new ArrayList<>();
            for (String f : c.stringList("indexFiles")) {
                if (!f.isBlank()) index.add(Path.of(f));
            }
            for (String f : c.stringList("javaFiles")) s.files.add(new FileRef(Kind.JAVA, new File(f)));
            for (String f : c.stringList("kotlinFiles")) s.files.add(new FileRef(Kind.KOTLIN, new File(f)));
            for (String f : c.stringList("groovyFiles")) s.files.add(new FileRef(Kind.GROOVY, new File(f)));
            for (String f : c.stringList("scalaFiles")) s.files.add(new FileRef(Kind.SCALA, new File(f)));
            if (index.isEmpty()) {
                for (FileRef r : s.files) index.add(r.file.toPath());
            }
            s.indexFiles = List.copyOf(index);
            return s;
        }

        /**
         * A tuning knob from a system property, or {@code fallback} when unset or unparseable. These
         * reach the worker through {@code [jvm] args} or {@code JK_JVM_ARGS}, the same way any other
         * worker-JVM flag does.
         */
        private static boolean hasProperty(String name) {
            String raw = System.getProperty(name);
            return raw != null && !raw.isBlank();
        }

        private static long longProperty(String name, long fallback) {
            String raw = System.getProperty(name);
            if (raw == null || raw.isBlank()) return fallback;
            try {
                return Long.parseLong(raw.strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static Set<File> jars(List<String> paths) {
            Set<File> out = new LinkedHashSet<>();
            for (String path : paths) {
                if (!path.isBlank()) out.add(new File(path));
            }
            return out;
        }
    }
}
