// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.tool.ToolResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code jk format} plan: collect sources, resolve formatter jars, fork {@code jk-formatter}.
 * Per-file results stream via {@link FileObserver}; plugin exit is a result on {@link #WORKER_EXIT}
 * ({@code --check} drift is not a plan failure — see {@link #reconcile}, which decides what is).
 */
public final class FormatPlans {

    private FormatPlans() {}

    // jk-pinned formatter impl versions (resolved via jk; the plugin uses these).
    public static final String PALANTIR_VERSION = "2.80.0";
    public static final String GOOGLE_VERSION = "1.28.0";
    public static final String KTFMT_VERSION = "0.61";
    public static final int KOTLIN_MAX_WIDTH = 120; // match Palantir's 120-col

    // palantir/google-java-format reflectively use the JDK compiler internals.
    private static final List<String> JAVAC_EXPORTS = List.of(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");

    /**
     * Receives each file's result as the plugin streams it: {@code status} is {@code changed},
     * {@code clean}, {@code skipped}, {@code unparseable} or {@code error}.
     *
     * <p>{@code unparseable} — OpenRewrite could not parse the file, so the import-shortening pass
     * never ran on it — is per-file only. {@link #CHANGED}/{@link #CLEAN}/{@link #ERRORS} are the
     * plan's published tallies and none of them claims it; the client counts it off this stream.
     * So the three published tallies do <em>not</em> sum to {@link #TOTAL} on a tree with
     * unparseable files: the fifth tally is counted inside {@link #runWorker} for the completeness
     * check and deliberately stays there rather than becoming a fourth number on the wire.
     */
    public interface FileObserver {
        void onFile(String path, String status, String message, int index, int total);
    }

    /**
     * Whether a per-file result may be recorded in the mtime/size {@link FormatFreshnessIndex}.
     *
     * <p>That index is the <em>outer</em> filter: a recorded path is not sent to the worker at all
     * on the next run, so recording one is a claim that the file is finished. {@code error} was
     * always excluded. {@code unparseable} is excluded for a sharper reason — recording it would
     * make the finding vanish on the second run, which is the exact shape of the bug the status
     * exists to end. The worker keeps its own content stamp for those, so they stay cheap.
     */
    static boolean recordsFreshness(String status, boolean check) {
        if ("error".equals(status) || "unparseable".equals(status)) return false;
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
     * Build the format plan for {@code projectDir}. Style names arrive already resolved (flags/env/
     * {@code [format]} block are the client's concern). Steps: {@code collect-sources} (SYNC) walks
     * the tree, {@code resolve-formatters} (IO) pulls the impl jars via {@link ToolResolver}, {@code
     * format} (IO) forks the plugin and streams per-file results. A project with no sources
     * finishes successfully with {@link #TOTAL} = 0 and no plugin forked.
     */
    public static BuildPlan formatBuildPlan(
            Path projectDir,
            Path cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
            Path rewriteConfig,
            FileObserver observer) {
        BuildPlanKey<List> javaFilesKey = BuildPlanKey.of("format-java-files", List.class);
        BuildPlanKey<List> kotlinFilesKey = BuildPlanKey.of("format-kotlin-files", List.class);
        BuildPlanKey<List> javaJarsKey = BuildPlanKey.of("format-java-jars", List.class);
        BuildPlanKey<List> removeUnusedJarsKey = BuildPlanKey.of("format-remove-unused-jars", List.class);
        BuildPlanKey<List> kotlinJarsKey = BuildPlanKey.of("format-kotlin-jars", List.class);

        BuildPlanKey<List> compileClasspathKey = BuildPlanKey.of("format-compile-classpath", List.class);

        BuildPlanKey<FormatFreshnessIndex> indexKey = BuildPlanKey.of("format-index", FormatFreshnessIndex.class);
        // FormatKey.digest() — computed once in collect, and the name of BOTH format stores: this
        // index here, and the worker's per-file stamps (it rides the spec as `configKey`).
        BuildPlanKey<String> configKeyKey = BuildPlanKey.of("format-config-key", String.class);

        Task collect = Task.builder(TaskNames.COLLECT_SOURCES)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("collect sources");
                    CollectedSources all = collectSources(projectDir);
                    // Only the OpenRewrite pass reads it; resolving one for a Spotless-only run
                    // would be a lockfile read and a few hundred stats for nobody.
                    List<Path> compileClasspath =
                            optimizeImports || rewriteConfig != null ? compileClasspath(projectDir) : List.of();
                    ctx.put(compileClasspathKey, compileClasspath);
                    String configKey = configKey(
                            cache,
                            javaStyle,
                            kotlinStyle,
                            optimizeImports,
                            importOrder,
                            removeUnusedImports,
                            rewriteConfig,
                            compileClasspath);
                    ctx.put(configKeyKey, configKey == null ? "" : configKey);
                    FormatFreshnessIndex index = configKey == null
                            ? FormatFreshnessIndex.disabled(projectDir)
                            : FormatFreshnessIndex.open(cache, projectDir, configKey);
                    FormatFreshnessIndex.Split split = index.partition(all.javaFiles(), all.kotlinFiles());
                    ctx.put(javaFilesKey, split.dirtyJava());
                    ctx.put(kotlinFilesKey, split.dirtyKotlin());
                    ctx.put(PRE_CLEAN, split.clean());
                    ctx.put(indexKey, index);
                    ctx.put(TOTAL, all.total());
                    ctx.progress(1);
                })
                .build();

        Task resolve = Task.builder(TaskNames.RESOLVE_FORMATTERS)
                .stage(BuildStage.RESOLVE)
                .kind(TaskKind.IO)
                .requires(TaskNames.COLLECT_SOURCES)
                .ticks(1)
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Path> javaFiles = (List<Path>) ctx.require(javaFilesKey);
                    @SuppressWarnings("unchecked")
                    List<Path> kotlinFiles = (List<Path>) ctx.require(kotlinFilesKey);
                    if (javaFiles.isEmpty() && kotlinFiles.isEmpty()) {
                        ctx.put(javaJarsKey, List.of());
                        ctx.put(removeUnusedJarsKey, List.of());
                        ctx.put(kotlinJarsKey, List.of());
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("resolve formatter jars");
                    var resolver = ToolResolver.mavenCentral(new Http(), JkStores.cas(cache));
                    try {
                        if (javaFiles.isEmpty()) {
                            ctx.put(javaJarsKey, List.of());
                            ctx.put(removeUnusedJarsKey, List.of());
                        } else {
                            List<Path> styleJars = resolver.resolve(javaCoord(javaStyle), "java-format", "ignored")
                                    .classpath();
                            ctx.put(javaJarsKey, styleJars);
                            // removeUnusedImports uses google-java-format under the hood; skip the
                            // extra resolve when the step is off. When style is already GJF
                            // (google/aosp) reuse those jars; Palantir needs a separate GJF resolve.
                            if (!removeUnusedImports) {
                                ctx.put(removeUnusedJarsKey, List.of());
                            } else if ("palantir".equals(javaStyle)) {
                                ctx.put(
                                        removeUnusedJarsKey,
                                        resolver.resolve(
                                                        Coordinate.of(
                                                                "com.google.googlejavaformat",
                                                                "google-java-format",
                                                                GOOGLE_VERSION),
                                                        "java-format-remove-unused",
                                                        "ignored")
                                                .classpath());
                            } else {
                                ctx.put(removeUnusedJarsKey, styleJars);
                            }
                        }
                        ctx.put(
                                kotlinJarsKey,
                                kotlinFiles.isEmpty()
                                        ? List.of()
                                        : resolver.resolve(
                                                        Coordinate.of("com.facebook", "ktfmt", KTFMT_VERSION),
                                                        "ktfmt",
                                                        "ignored")
                                                .classpath());
                    } catch (RuntimeException e) {
                        ctx.error("resolve", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Task format = Task.builder("format")
                .kind(TaskKind.IO)
                .requires(TaskNames.RESOLVE_FORMATTERS)
                .ticks(0) // grown to the real file count once collected
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Path> javaFiles = (List<Path>) ctx.require(javaFilesKey);
                    @SuppressWarnings("unchecked")
                    List<Path> kotlinFiles = (List<Path>) ctx.require(kotlinFilesKey);
                    int preClean = ctx.get(PRE_CLEAN).orElse(0);
                    FormatFreshnessIndex freshness = ctx.get(indexKey).orElse(null);
                    int dirty = javaFiles.size() + kotlinFiles.size();
                    int total = preClean + dirty;
                    ctx.put(TOTAL, total);
                    if (dirty == 0) {
                        ctx.put(CHANGED, 0);
                        ctx.put(CLEAN, preClean);
                        ctx.put(ERRORS, 0);
                        ctx.put(WORKER_EXIT, 0);
                        if (total > 0) {
                            ctx.updateTicks(total);
                            ctx.progress(total);
                        }
                        return;
                    }
                    ctx.updateTicks(total);
                    if (preClean > 0) ctx.progress(preClean);
                    ctx.label(check ? "check formatting" : "format sources");
                    @SuppressWarnings("unchecked")
                    List<Path> javaJars = (List<Path>) ctx.require(javaJarsKey);
                    @SuppressWarnings("unchecked")
                    List<Path> removeUnusedJars = (List<Path>) ctx.require(removeUnusedJarsKey);
                    @SuppressWarnings("unchecked")
                    List<Path> kotlinJars = (List<Path>) ctx.require(kotlinJarsKey);

                    Path workerJar = PluginJar.FORMATTER.locate(JkStores.cas(cache));
                    String configKey = ctx.get(configKeyKey).orElse("");
                    @SuppressWarnings("unchecked")
                    List<Path> compileClasspath =
                            (List<Path>) ctx.get(compileClasspathKey).orElse(List.of());
                    Path spec = writeSpec(
                            check,
                            javaStyle,
                            kotlinStyle,
                            javaFiles,
                            javaJars,
                            removeUnusedJars,
                            kotlinFiles,
                            kotlinJars,
                            optimizeImports,
                            importOrder,
                            removeUnusedImports,
                            rewriteConfig,
                            compileClasspath,
                            cache,
                            configKey.isEmpty() ? null : configKey,
                            null);
                    try {
                        Path hostJava = JavaHomes.runningJavaHome();
                        String workerCp = WorkerLaunchClasspath.resolve(workerJar);
                        List<String> extra = new ArrayList<>(PluginAot.formatterFlags(
                                hostJava,
                                workerCp,
                                (aotOut, scratch) -> trainerCommand(
                                        hostJava,
                                        workerCp,
                                        aotOut,
                                        scratch,
                                        javaStyle,
                                        kotlinStyle,
                                        javaJars,
                                        removeUnusedJars,
                                        kotlinJars,
                                        optimizeImports,
                                        importOrder,
                                        removeUnusedImports)));
                        if (!javaFiles.isEmpty()) extra.addAll(JAVAC_EXPORTS);
                        runWorker(
                                ctx,
                                PluginLaunch.javaCommand(workerJar, extra, spec),
                                preClean,
                                total,
                                check,
                                freshness,
                                observer);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("format worker interrupted", e);
                    } finally {
                        Files.deleteIfExists(spec);
                    }
                })
                .build();

        return BuildPlan.builder("format")
                .addTask(collect)
                .addTask(resolve)
                .addTask(format)
                .build();
    }

    /**
     * Fork the formatter worker, tally its per-file stream, publish the run's counts — and refuse to
     * let the step succeed unless the tally {@linkplain #reconcile reconciles} with {@code total}.
     *
     * <p>Every surface that says whether a format run worked reads {@code BuildPlanResult.success()}
     * — journal and dashboard via {@code FormatVerb}, terminal wedge via {@code FormatCommand}.
     * Before this check the step could not fail at all, so a worker that died at file 500 of 2,063 (a
     * HotSpot SIGSEGV under a full GC, measured here — see {@link #compileClasspath}) journaled as a
     * successful, complete format with nothing changed and no errors, while the exit code the CLI
     * returned one line later said 139. Two readers of one fact, disagreeing.
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
        // The fifth tally, and the only one the plan does not publish (JK-2477 keeps `unparseable`
        // per-file). It is counted here because the sum has to balance: a file OpenRewrite could not
        // parse was visited, and leaving it out would read as a file the worker never reached.
        AtomicInteger unparseable = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();
        int exit = new PluginClient("##JKFMT:")
                .on("file", json -> {
                    String status = Jsonl.str(json, "status");
                    String path = Jsonl.str(json, "path");
                    if ("changed".equals(status)) {
                        changed.incrementAndGet();
                    } else if ("error".equals(status)) {
                        errors.incrementAndGet();
                    } else if ("unparseable".equals(status)) {
                        unparseable.incrementAndGet();
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
        int reported = preClean + changed.get() + clean.get() + errors.get() + unparseable.get();
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

    private static Coordinate javaCoord(String style) {
        return "palantir".equals(style)
                ? Coordinate.of("com.palantir.javaformat", "palantir-java-format", PALANTIR_VERSION)
                : Coordinate.of("com.google.googlejavaformat", "google-java-format", GOOGLE_VERSION);
    }

    private static String javaVersion(String style) {
        return "palantir".equals(style) ? PALANTIR_VERSION : GOOGLE_VERSION;
    }

    // Package-private so FormatKeyTest can assert the worker is actually told the key.
    static Path writeSpec(
            boolean check,
            String javaStyle,
            String kotlinStyle,
            List<Path> javaFiles,
            List<Path> javaJars,
            List<Path> removeUnusedJars,
            List<Path> kotlinFiles,
            List<Path> kotlinJars,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
            Path rewriteConfig,
            List<Path> compileClasspath,
            Path cacheDir,
            String configKey,
            Path dest)
            throws IOException {
        SpecWriter w = new SpecWriter()
                .op(PluginProtocol.OP_COMMAND, "format", "jk-formatter")
                .configBool("apply", !check);
        if (!javaFiles.isEmpty()) {
            w.configString("javaStyle", javaStyle)
                    .configString("javaVersion", javaVersion(javaStyle))
                    .configList("javaJars", absPaths(javaJars))
                    .configList("javaFiles", absPaths(javaFiles))
                    .configBool("importOrder", importOrder)
                    .configBool("removeUnusedImports", removeUnusedImports);
            // Distinct GJF classpath for removeUnusedImports when style is Palantir; empty when the
            // style jars already are GJF (plugin falls back to javaJars).
            if (removeUnusedImports
                    && removeUnusedJars != null
                    && !removeUnusedJars.isEmpty()
                    && !samePaths(javaJars, removeUnusedJars)) {
                w.configList("removeUnusedJars", absPaths(removeUnusedJars));
            }
        }
        if (!kotlinFiles.isEmpty()) {
            w.configString("kotlinStyle", kotlinStyle)
                    .configString("kotlinVersion", KTFMT_VERSION)
                    .configInt("kotlinMaxWidth", KOTLIN_MAX_WIDTH)
                    .configList("kotlinJars", absPaths(kotlinJars))
                    .configList("kotlinFiles", absPaths(kotlinFiles));
        }
        if ((optimizeImports || rewriteConfig != null) && !javaFiles.isEmpty()) {
            w.configBool("optimizeImports", optimizeImports);
            // OpenRewrite can only shorten a name it can resolve, and it resolves against this.
            // Standard `cp` lines with the compile role, so the worker reads it through
            // PluginSpec.compileClasspath() like every other worker.
            if (compileClasspath != null) w.classpath(compileClasspath, PluginProtocol.ROLE_COMPILE);
            if (rewriteConfig != null)
                w.configString(
                        "rewriteConfigFile", rewriteConfig.toAbsolutePath().toString());
        }
        // The stamp store's root and its key. The worker derives neither: a second derivation of
        // "the formatter config" is what let kotlinMaxWidth and the GJF version go unkeyed.
        if (cacheDir != null && configKey != null) {
            w.configString("cacheDir", cacheDir.toAbsolutePath().toString());
            w.configString("configKey", configKey);
        }
        Path spec = dest != null ? dest : Files.createTempFile("jk-format-", ".spec");
        if (dest != null && dest.getParent() != null) Files.createDirectories(dest.getParent());
        Files.write(spec, w.lines(), StandardCharsets.UTF_8);
        return spec;
    }

    // The trainer's stamp store is the scratch dir, deleted with it. A fixed key keeps the training
    // spec the same shape as a real one so the stamp path lands in the AOT cache.
    private static final String TRAIN_CONFIG_KEY = "format-aot-train";

    /**
     * Background AOT trainer: same {@code java -cp worker PluginMain spec} shape as a real format,
     * recording with {@code -XX:AOTCacheOutput} while formatting a synthetic Hello.java (and
     * Hello.kt when Kotlin jars are on this run).
     */
    static List<String> trainerCommand(
            Path hostJavaHome,
            String workerCp,
            Path aotOutput,
            Path scratch,
            String javaStyle,
            String kotlinStyle,
            List<Path> javaJars,
            List<Path> removeUnusedJars,
            List<Path> kotlinJars,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports)
            throws IOException {
        List<Path> javaFiles = List.of();
        if (javaJars != null && !javaJars.isEmpty()) {
            Path hello = scratch.resolve("Hello.java");
            Files.writeString(hello, TRAIN_JAVA);
            javaFiles = List.of(hello);
        }
        List<Path> kotlinFiles = List.of();
        if (kotlinJars != null && !kotlinJars.isEmpty()) {
            Path helloKt = scratch.resolve("Hello.kt");
            Files.writeString(helloKt, TRAIN_KOTLIN);
            kotlinFiles = List.of(helloKt);
        }
        if (javaFiles.isEmpty() && kotlinFiles.isEmpty()) {
            // Nothing to exercise — still emit a no-op spec so the worker starts and the
            // PluginMain + Spotless classes land in the cache.
            Path hello = scratch.resolve("Hello.java");
            Files.writeString(hello, TRAIN_JAVA);
            javaFiles = List.of(hello);
        }
        Path spec = writeSpec(
                false,
                javaStyle,
                kotlinStyle,
                javaFiles,
                javaJars == null ? List.of() : javaJars,
                removeUnusedJars == null ? List.of() : removeUnusedJars,
                kotlinFiles,
                kotlinJars == null ? List.of() : kotlinJars,
                optimizeImports,
                importOrder,
                removeUnusedImports,
                null,
                // The trainer formats a synthetic Hello.java that names only java.*; a real
                // classpath would cost the AOT run a few hundred jar opens for no extra class.
                List.of(),
                scratch,
                TRAIN_CONFIG_KEY,
                scratch.resolve("train.spec"));
        List<String> jvmFlags = new ArrayList<>();
        jvmFlags.add("-XX:AOTCacheOutput=" + aotOutput);
        jvmFlags.addAll(JvmOptions.batchFlags(1));
        if (!javaFiles.isEmpty()) jvmFlags.addAll(JAVAC_EXPORTS);
        Path javaExe = JdkFingerprint.java(hostJavaHome);
        return PluginLoader.command(
                javaExe, workerCp, jvmFlags, List.of(spec.toAbsolutePath().toString()));
    }

    private static final String TRAIN_JAVA = """
            package demo;

            import java.util.ArrayList;
            import java.util.List;

            public class Hello {
              public static void main(String[] args) {
                java.util.Map<String, Integer> values = new java.util.HashMap<>();
                values.put("a", 1);
                List<String> names = new ArrayList<>();
                names.add("jk-formatter aot train");
                System.out.println(values + names.toString());
              }
            }
            """;

    private static final String TRAIN_KOTLIN = """
            package demo

            data class Point(val x: Int, val y: Int)

            fun main() {
                val points = (1..4).map { Point(it, it * 2) }
                println(points.joinToString { "${it.x},${it.y}" })
            }
            """;

    private static List<String> absPaths(List<Path> paths) {
        return paths.stream().map(p -> p.toAbsolutePath().toString()).toList();
    }

    /** True when both lists contain the same absolute paths (order-insensitive). */
    private static boolean samePaths(List<Path> a, List<Path> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        var left = new LinkedHashSet<String>();
        for (Path p : a) left.add(p.toAbsolutePath().toString());
        var right = new LinkedHashSet<String>();
        for (Path p : b) right.add(p.toAbsolutePath().toString());
        return left.equals(right);
    }

    record CollectedSources(List<Path> javaFiles, List<Path> kotlinFiles) {
        int total() {
            return javaFiles.size() + kotlinFiles.size();
        }
    }

    /**
     * One walk, skipping excluded directories entirely ({@code target/}, {@code build/}, {@code
     * .git/}, …) instead of descending and filtering files afterwards.
     */
    static CollectedSources collectSources(Path root) throws IOException {
        if (!Files.isDirectory(root)) return new CollectedSources(List.of(), List.of());
        List<Path> java = new ArrayList<>();
        List<Path> kotlin = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                return excludedSegment(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path rel;
                try {
                    rel = root.relativize(file);
                } catch (IllegalArgumentException e) {
                    rel = file;
                }
                if (!notExcluded(rel)) return FileVisitResult.CONTINUE;
                String name = file.getFileName().toString();
                if (name.endsWith(".java")) java.add(file);
                else if (name.endsWith(".kt")) kotlin.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        java.sort(null);
        kotlin.sort(null);
        return new CollectedSources(List.copyOf(java), List.copyOf(kotlin));
    }

    /**
     * The classpath {@code optimize-imports} resolves type names against — without one OpenRewrite
     * attributes every name to {@code Unknown}, so the pass parses every file and shortens nothing.
     *
     * <p>It is every workspace module's class output, taken from the lockfile's module list. That
     * is what makes the project's <em>own</em> types nameable, and a tree's own types are what it
     * writes fully-qualified: on jk itself, 3,798 of 4,284 fully-qualified references.
     *
     * <p><strong>Dependency jars are deliberately not on it,</strong> and this is the one place
     * that says so. OpenRewrite builds a javac file manager per file, so every classpath entry is
     * re-opened for every file parsed. Measured over jk's own 2,011 sources: these directories cost
     * 18s on top of a 108s {@code jk format} and shorten 3,293 references; adding the lockfile's
     * 150 dependency jars shortens 47 more (1.4%) and costs a further 6 minutes. It also runs the
     * worker's heap hard enough to have crashed it (a HotSpot SIGSEGV unloading classes under a
     * full GC) in two of four whole-tree runs. A dependency's type written out in full therefore
     * stays that way, which is a stated limit in {@code docs/user/format.md}, not an accident.
     *
     * <p>Directories that do not exist yet are still listed: javac ignores them, and dropping them
     * would make the entry list — and therefore {@link FormatKey#digest()} — change every time a
     * module's tests first compile, re-formatting the tree for nothing. A module that has never
     * been built simply contributes no types, and its callers keep their fully-qualified names
     * until it has.
     *
     * <p>No lockfile means no classpath: {@code jk format} does not resolve or fetch anything, and
     * shortening degrades to the JDK types javac supplies on its own.
     */
    static List<Path> compileClasspath(Path projectDir) {
        Path lockFile = LockPaths.lockFile(projectDir);
        if (!Files.isRegularFile(lockFile)) return List.of();
        Lockfile lock;
        try {
            lock = LockfileReader.read(lockFile);
        } catch (Exception e) {
            return List.of();
        }
        Path workspaceRoot = LockPaths.lockOwnerDir(projectDir);
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        for (Lockfile.ModuleEntry module : lock.modules()) {
            Path target = BuildLayout.moduleTargetDir(workspaceRoot, workspaceRoot.resolve(module.path()));
            entries.add(target.resolve("classes").resolve("main"));
            entries.add(target.resolve("classes").resolve("test"));
        }
        return List.copyOf(entries);
    }

    /**
     * This run's {@link FormatKey} digest, or null when the worker jar cannot be located — without
     * the formatter's own identity there is no honest key, so both stores stay off rather than
     * cache under a key that cannot see a formatter upgrade.
     *
     * <p>Package-private so {@code FormatKeyTest} can assert the jk-pinned width and
     * google-java-format version actually reach the key.
     */
    static String configKey(
            Path cache,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
            Path rewriteConfig,
            List<Path> compileClasspath) {
        try {
            return new FormatKey(
                            javaStyle,
                            javaVersion(javaStyle),
                            kotlinStyle,
                            KTFMT_VERSION,
                            KOTLIN_MAX_WIDTH,
                            optimizeImports,
                            importOrder,
                            removeUnusedImports,
                            GOOGLE_VERSION,
                            rewriteConfig,
                            compileClasspath,
                            PluginJar.FORMATTER.locate(JkStores.cas(cache)))
                    .digest();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean notExcluded(Path p) {
        for (Path seg : p) {
            if (excludedSegment(seg.toString())) return false;
        }
        return true;
    }

    /**
     * Directory (or path-segment) names we never format under. A giter8 template root is a
     * directory literally suffixed {@code .g8} or named {@code g8}. A bare "templates"/"giter8"
     * segment is not excluded — this repo's {@code cc.jumpkick.templates} package is real source.
     */
    static boolean excludedSegment(String s) {
        if (s.equals(BuildLayout.TARGET)
                || s.equals("build")
                || s.equals(".jk")
                || s.equals(".git")
                || s.equals("node_modules")) {
            return true;
        }
        if (s.endsWith(".g8") || s.equals("g8")) return true;
        return s.length() > 1 && s.startsWith("$") && s.endsWith("$");
    }
}
