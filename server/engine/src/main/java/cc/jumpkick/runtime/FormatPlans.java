// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.tool.ToolResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * {@code jk format} plan: collect sources ({@link FormatSources}), resolve formatter jars, fork
 * {@code jk-formatter} ({@link FormatWorker}). Per-file results stream via
 * {@link FormatWorker.FileObserver}; plugin exit is a result on {@link FormatWorker#WORKER_EXIT}
 * ({@code --check} drift is not a plan failure — {@link FormatWorker#reconcile} decides what is).
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
     * Build the format plan for {@code projectDir}. Style names arrive already resolved (flags/env/
     * {@code [format]} block are the client's concern). Steps: {@code collect-sources} (SYNC) walks
     * the tree, {@code resolve-formatters} (IO) pulls the impl jars via {@link ToolResolver}, {@code
     * format} (IO) forks the plugin and streams per-file results. A project with no sources
     * finishes successfully with {@link FormatWorker#TOTAL} = 0 and no plugin forked.
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
            FormatWorker.FileObserver observer) {
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
                    FormatSources.CollectedSources all = FormatSources.collectSources(projectDir);
                    // Only the OpenRewrite pass reads it; resolving one for a Spotless-only run
                    // would be a lockfile read and a few hundred stats for nobody.
                    List<Path> compileClasspath = optimizeImports || rewriteConfig != null
                            ? FormatSources.compileClasspath(projectDir)
                            : List.of();
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
                    ctx.put(FormatWorker.PRE_CLEAN, split.clean());
                    ctx.put(indexKey, index);
                    ctx.put(FormatWorker.TOTAL, all.total());
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
                    int preClean = ctx.get(FormatWorker.PRE_CLEAN).orElse(0);
                    FormatFreshnessIndex freshness = ctx.get(indexKey).orElse(null);
                    int dirty = javaFiles.size() + kotlinFiles.size();
                    int total = preClean + dirty;
                    ctx.put(FormatWorker.TOTAL, total);
                    if (dirty == 0) {
                        ctx.put(FormatWorker.CHANGED, 0);
                        ctx.put(FormatWorker.CLEAN, preClean);
                        ctx.put(FormatWorker.ERRORS, 0);
                        ctx.put(FormatWorker.WORKER_EXIT, 0);
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
                        FormatWorker.runWorker(
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
        // This fork goes through PluginLoader.command, not PluginLaunch — seal at the producer.
        PluginLoader.sealNetworkPolicy(spec);
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
}
