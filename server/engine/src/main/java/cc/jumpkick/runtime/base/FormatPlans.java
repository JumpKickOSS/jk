// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.JdkCompilerAccess;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.host.Errors;
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
import cc.jumpkick.run.TaskContext;
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
import org.jspecify.annotations.Nullable;

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
    // Spotless 4.6.2's ScalaFmtStep.defaultVersion(); glue is compiled against this line.
    public static final String SCALAFMT_VERSION = "3.8.1";

    // palantir/google-java-format reflectively use the JDK compiler internals.
    private static final List<String> JAVAC_EXPORTS = JdkCompilerAccess.JVM_FLAGS;

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
            @Nullable String javaStyle,
            @Nullable String kotlinStyle,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
            FormatWorker.FileObserver observer) {
        Options o = new Options(
                projectDir,
                cache,
                check,
                javaStyle,
                kotlinStyle,
                optimizeImports,
                importOrder,
                removeUnusedImports,
                observer);
        Keys k = Keys.create();
        return BuildPlan.builder("format")
                .stateKeys(
                        k.javaFilesKey(),
                        k.kotlinFilesKey(),
                        k.groovyFilesKey(),
                        k.scalaFilesKey(),
                        k.javaJarsKey(),
                        k.removeUnusedJarsKey(),
                        k.kotlinJarsKey(),
                        k.scalaJarsKey(),
                        k.allJavaFilesKey(),
                        k.allKotlinFilesKey(),
                        k.allGroovyFilesKey(),
                        k.allScalaFilesKey(),
                        k.indexKey(),
                        k.configKeyKey(),
                        FormatWorker.PRE_CLEAN,
                        FormatWorker.TOTAL,
                        FormatWorker.CHANGED,
                        FormatWorker.CLEAN,
                        FormatWorker.ERRORS,
                        FormatWorker.WORKER_EXIT)
                .addTask(collectStep(o, k))
                .addTask(resolveStep(o, k))
                .addTask(formatStep(o, k))
                .build();
    }

    /** The request as the three steps read it. */
    private record Options(
            Path projectDir,
            Path cache,
            boolean check,
            @Nullable String javaStyle,
            @Nullable String kotlinStyle,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
            FormatWorker.FileObserver observer) {}

    /** The plan keys the steps hand each other: dirty files, every file, jars, index and config key. */
    private record Keys(
            BuildPlanKey<List<Path>> javaFilesKey,
            BuildPlanKey<List<Path>> kotlinFilesKey,
            BuildPlanKey<List<Path>> groovyFilesKey,
            BuildPlanKey<List<Path>> scalaFilesKey,
            BuildPlanKey<List<Path>> javaJarsKey,
            BuildPlanKey<List<Path>> removeUnusedJarsKey,
            BuildPlanKey<List<Path>> kotlinJarsKey,
            BuildPlanKey<List<Path>> scalaJarsKey,
            BuildPlanKey<List<Path>> allJavaFilesKey,
            BuildPlanKey<List<Path>> allKotlinFilesKey,
            BuildPlanKey<List<Path>> allGroovyFilesKey,
            BuildPlanKey<List<Path>> allScalaFilesKey,
            BuildPlanKey<FormatFreshnessIndex> indexKey,
            BuildPlanKey<String> configKeyKey) {
        static Keys create() {
            return new Keys(
                    BuildPlanKey.list("format-java-files", Path.class),
                    BuildPlanKey.list("format-kotlin-files", Path.class),
                    BuildPlanKey.list("format-groovy-files", Path.class),
                    BuildPlanKey.list("format-scala-files", Path.class),
                    BuildPlanKey.list("format-java-jars", Path.class),
                    BuildPlanKey.list("format-remove-unused-jars", Path.class),
                    BuildPlanKey.list("format-kotlin-jars", Path.class),
                    BuildPlanKey.list("format-scala-jars", Path.class),
                    BuildPlanKey.list("format-all-java-files", Path.class),
                    BuildPlanKey.list("format-all-kotlin-files", Path.class),
                    BuildPlanKey.list("format-all-groovy-files", Path.class),
                    BuildPlanKey.list("format-all-scala-files", Path.class),
                    BuildPlanKey.scalar("format-index", FormatFreshnessIndex.class),
                    // FormatKey.digest() — computed once in collect, and the name of BOTH format
                    // stores: this index here, and the worker's per-file stamps (it rides the spec
                    // as `configKey`).
                    BuildPlanKey.scalar("format-config-key", String.class));
        }
    }

    /** Walk the tree, compute the config key, and partition the sources by freshness. */
    private static Task collectStep(Options o, Keys k) {
        return Task.builder(TaskNames.COLLECT_SOURCES)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("collect sources");
                    FormatSources.CollectedSources all = FormatSources.collectSources(o.projectDir());
                    ctx.put(k.allJavaFilesKey(), all.javaFiles());
                    ctx.put(k.allKotlinFilesKey(), all.kotlinFiles());
                    ctx.put(k.allGroovyFilesKey(), all.groovyFiles());
                    ctx.put(k.allScalaFilesKey(), all.scalaFiles());
                    // The index's file set is a key input, so it is assembled here — before the
                    // freshness partition, from every source, not just the dirty ones.
                    List<Path> allSources = new ArrayList<>(all.javaFiles());
                    allSources.addAll(all.kotlinFiles());
                    allSources.addAll(all.groovyFiles());
                    allSources.addAll(all.scalaFiles());
                    String configKey = configKey(
                            o.cache(),
                            o.javaStyle(),
                            o.kotlinStyle(),
                            o.optimizeImports(),
                            o.importOrder(),
                            o.removeUnusedImports(),
                            allSources);
                    ctx.put(k.configKeyKey(), configKey == null ? "" : configKey);
                    FormatFreshnessIndex index = configKey == null
                            ? FormatFreshnessIndex.disabled(o.projectDir())
                            : FormatFreshnessIndex.open(o.cache(), o.projectDir(), configKey);
                    FormatFreshnessIndex.Split split =
                            index.partition(all.javaFiles(), all.kotlinFiles(), all.groovyFiles(), all.scalaFiles());
                    ctx.put(k.javaFilesKey(), split.dirtyJava());
                    ctx.put(k.kotlinFilesKey(), split.dirtyKotlin());
                    ctx.put(k.groovyFilesKey(), split.dirtyGroovy());
                    ctx.put(k.scalaFilesKey(), split.dirtyScala());
                    ctx.put(FormatWorker.PRE_CLEAN, split.clean());
                    ctx.put(k.indexKey(), index);
                    ctx.put(FormatWorker.TOTAL, all.total());
                    ctx.progress(1);
                })
                .build();
    }

    /** Pull the formatter impl jars for the languages that have dirty files. */
    private static Task resolveStep(Options o, Keys k) {
        return Task.builder(TaskNames.RESOLVE_FORMATTERS)
                .stage(BuildStage.RESOLVE)
                .kind(TaskKind.IO)
                .requires(TaskNames.COLLECT_SOURCES)
                .ticks(1)
                .execute(ctx -> {
                    List<Path> javaFiles = ctx.require(k.javaFilesKey());
                    List<Path> kotlinFiles = ctx.require(k.kotlinFilesKey());
                    List<Path> groovyFiles = ctx.require(k.groovyFilesKey());
                    List<Path> scalaFiles = ctx.require(k.scalaFilesKey());
                    if (javaFiles.isEmpty() && kotlinFiles.isEmpty() && groovyFiles.isEmpty() && scalaFiles.isEmpty()) {
                        ctx.put(k.javaJarsKey(), List.of());
                        ctx.put(k.removeUnusedJarsKey(), List.of());
                        ctx.put(k.kotlinJarsKey(), List.of());
                        ctx.put(k.scalaJarsKey(), List.of());
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("resolve formatter jars");
                    var resolver = ToolResolver.mavenCentral(Http.forRepositories(), JkStores.storeCas());
                    try {
                        if (javaFiles.isEmpty()) {
                            ctx.put(k.javaJarsKey(), List.of());
                            ctx.put(k.removeUnusedJarsKey(), List.of());
                        } else {
                            List<Path> styleJars = resolver.resolve(javaCoord(o.javaStyle()), "java-format", "ignored")
                                    .classpath();
                            ctx.put(k.javaJarsKey(), styleJars);
                            // o.removeUnusedImports() uses google-java-format under the hood; skip the
                            // extra resolve when the step is off. When style is already GJF
                            // (google/aosp) reuse those jars; Palantir needs a separate GJF resolve.
                            if (!o.removeUnusedImports()) {
                                ctx.put(k.removeUnusedJarsKey(), List.of());
                            } else if ("palantir".equals(o.javaStyle())) {
                                ctx.put(
                                        k.removeUnusedJarsKey(),
                                        resolver.resolve(
                                                        Coordinate.of(
                                                                "com.google.googlejavaformat",
                                                                "google-java-format",
                                                                GOOGLE_VERSION),
                                                        "java-format-remove-unused",
                                                        "ignored")
                                                .classpath());
                            } else {
                                ctx.put(k.removeUnusedJarsKey(), styleJars);
                            }
                        }
                        ctx.put(
                                k.kotlinJarsKey(),
                                kotlinFiles.isEmpty()
                                        ? List.of()
                                        : resolver.resolve(
                                                        Coordinate.of("com.facebook", "ktfmt", KTFMT_VERSION),
                                                        "ktfmt",
                                                        "ignored")
                                                .classpath());
                        ctx.put(
                                k.scalaJarsKey(),
                                scalaFiles.isEmpty()
                                        ? List.of()
                                        : resolver.resolve(
                                                        Coordinate.of(
                                                                "org.scalameta",
                                                                "scalafmt-core_2.13",
                                                                SCALAFMT_VERSION),
                                                        "scalafmt",
                                                        "ignored")
                                                .classpath());
                    } catch (RuntimeException e) {
                        ctx.error("resolve", Errors.text(e));
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /** Fork the plugin over the dirty files; a clean tree finishes without a fork. */
    private static Task formatStep(Options o, Keys k) {
        return Task.builder("format")
                .kind(TaskKind.IO)
                .requires(TaskNames.RESOLVE_FORMATTERS)
                .ticks(0) // grown to the real file count once collected
                .execute(ctx -> {
                    List<Path> javaFiles = ctx.require(k.javaFilesKey());
                    List<Path> kotlinFiles = ctx.require(k.kotlinFilesKey());
                    List<Path> groovyFiles = ctx.require(k.groovyFilesKey());
                    List<Path> scalaFiles = ctx.require(k.scalaFilesKey());
                    int preClean = ctx.get(FormatWorker.PRE_CLEAN).orElse(0);
                    FormatFreshnessIndex freshness = ctx.get(k.indexKey()).orElse(null);
                    int dirty = javaFiles.size() + kotlinFiles.size() + groovyFiles.size() + scalaFiles.size();
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
                    ctx.label(o.check() ? "check formatting" : "format sources");
                    runFormatter(ctx, o, k, preClean, total, freshness);
                })
                .build();
    }

    /** Write the spec and fork {@code jk-formatter}, streaming per-file results to the observer. */
    private static void runFormatter(
            TaskContext ctx, Options o, Keys k, int preClean, int total, @Nullable FormatFreshnessIndex freshness)
            throws IOException {
        List<Path> javaFiles = ctx.require(k.javaFilesKey());
        List<Path> kotlinFiles = ctx.require(k.kotlinFilesKey());
        List<Path> groovyFiles = ctx.require(k.groovyFilesKey());
        List<Path> scalaFiles = ctx.require(k.scalaFilesKey());
        List<Path> javaJars = ctx.require(k.javaJarsKey());
        List<Path> removeUnusedJars = ctx.require(k.removeUnusedJarsKey());
        List<Path> kotlinJars = ctx.require(k.kotlinJarsKey());
        List<Path> scalaJars = ctx.require(k.scalaJarsKey());

        Path workerJar = PluginJar.FORMATTER.locate(JkStores.storeCas());
        String configKey = ctx.get(k.configKeyKey()).orElse("");
        List<Path> allJava = ctx.get(k.allJavaFilesKey()).orElse(javaFiles);
        List<Path> allKotlin = ctx.get(k.allKotlinFilesKey()).orElse(kotlinFiles);
        List<Path> allGroovy = ctx.get(k.allGroovyFilesKey()).orElse(groovyFiles);
        List<Path> allScala = ctx.get(k.allScalaFilesKey()).orElse(scalaFiles);
        List<Path> indexFiles = new ArrayList<>(allJava);
        indexFiles.addAll(allKotlin);
        indexFiles.addAll(allGroovy);
        indexFiles.addAll(allScala);
        Path spec = writeSpec(
                o.check(),
                o.javaStyle(),
                o.kotlinStyle(),
                javaFiles,
                javaJars,
                removeUnusedJars,
                kotlinFiles,
                kotlinJars,
                groovyFiles,
                scalaFiles,
                scalaJars,
                o.optimizeImports(),
                o.importOrder(),
                o.removeUnusedImports(),
                indexFiles,
                o.cache(),
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
                            o.javaStyle(),
                            o.kotlinStyle(),
                            javaJars,
                            removeUnusedJars,
                            kotlinJars,
                            scalaJars,
                            !groovyFiles.isEmpty(),
                            o.optimizeImports(),
                            o.importOrder(),
                            o.removeUnusedImports())));
            if (!javaFiles.isEmpty()) extra.addAll(JAVAC_EXPORTS);
            // The run's only fork, so it gets the machine rather than the build-shaped
            // 1/jobs share the process-wide plan hands every worker.
            extra.addAll(JvmOptions.soleWorkerFlags());
            FormatWorker.runWorker(
                    ctx,
                    PluginLaunch.javaCommand(workerJar, extra, spec),
                    preClean,
                    total,
                    o.check(),
                    freshness,
                    o.observer());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("format worker interrupted", e);
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private static Coordinate javaCoord(@Nullable String style) {
        return "palantir".equals(style)
                ? Coordinate.of("com.palantir.javaformat", "palantir-java-format", PALANTIR_VERSION)
                : Coordinate.of("com.google.googlejavaformat", "google-java-format", GOOGLE_VERSION);
    }

    private static String javaVersion(@Nullable String style) {
        return "palantir".equals(style) ? PALANTIR_VERSION : GOOGLE_VERSION;
    }

    // Package-private so FormatKeyTest can assert the worker is actually told the key.
    static Path writeSpec(
            boolean check,
            @Nullable String javaStyle,
            @Nullable String kotlinStyle,
            List<Path> javaFiles,
            List<Path> javaJars,
            List<Path> removeUnusedJars,
            List<Path> kotlinFiles,
            List<Path> kotlinJars,
            List<Path> groovyFiles,
            List<Path> scalaFiles,
            List<Path> scalaJars,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
            List<Path> indexFiles,
            Path cacheDir,
            @Nullable String configKey,
            @Nullable Path dest)
            throws IOException {
        if (groovyFiles == null) groovyFiles = List.of();
        if (scalaFiles == null) scalaFiles = List.of();
        if (scalaJars == null) scalaJars = List.of();
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
        if (!groovyFiles.isEmpty()) {
            w.configList("groovyFiles", absPaths(groovyFiles));
        }
        if (!scalaFiles.isEmpty()) {
            w.configString("scalaVersion", SCALAFMT_VERSION)
                    .configList("scalaJars", absPaths(scalaJars))
                    .configList("scalaFiles", absPaths(scalaFiles));
        }
        if (optimizeImports
                && (indexFiles != null && !indexFiles.isEmpty()
                        || !javaFiles.isEmpty()
                        || !kotlinFiles.isEmpty()
                        || !groovyFiles.isEmpty()
                        || !scalaFiles.isEmpty())) {
            w.configBool("optimizeImports", true);
            if (indexFiles != null && !indexFiles.isEmpty()) {
                w.configList("indexFiles", absPaths(indexFiles));
            }
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
     * Hello.kt / Hello.groovy / Hello.scala when those languages are on this run).
     */
    static List<String> trainerCommand(
            Path hostJavaHome,
            String workerCp,
            Path aotOutput,
            Path scratch,
            @Nullable String javaStyle,
            @Nullable String kotlinStyle,
            List<Path> javaJars,
            List<Path> removeUnusedJars,
            List<Path> kotlinJars,
            List<Path> scalaJars,
            boolean trainGroovy,
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
        List<Path> groovyFiles = List.of();
        if (trainGroovy) {
            Path helloGroovy = scratch.resolve("Hello.groovy");
            Files.writeString(helloGroovy, TRAIN_GROOVY);
            groovyFiles = List.of(helloGroovy);
        }
        List<Path> scalaFiles = List.of();
        if (scalaJars != null && !scalaJars.isEmpty()) {
            Path helloScala = scratch.resolve("Hello.scala");
            Files.writeString(helloScala, TRAIN_SCALA);
            scalaFiles = List.of(helloScala);
        }
        if (javaFiles.isEmpty() && kotlinFiles.isEmpty() && groovyFiles.isEmpty() && scalaFiles.isEmpty()) {
            // Nothing to exercise — still emit a no-op spec so the worker starts and the
            // PluginMain + Spotless classes land in the cache.
            Path hello = scratch.resolve("Hello.java");
            Files.writeString(hello, TRAIN_JAVA);
            javaFiles = List.of(hello);
        }
        List<Path> indexFiles = new ArrayList<>(javaFiles);
        indexFiles.addAll(kotlinFiles);
        indexFiles.addAll(groovyFiles);
        indexFiles.addAll(scalaFiles);
        Path spec = writeSpec(
                false,
                javaStyle,
                kotlinStyle,
                javaFiles,
                javaJars == null ? List.of() : javaJars,
                removeUnusedJars == null ? List.of() : removeUnusedJars,
                kotlinFiles,
                kotlinJars == null ? List.of() : kotlinJars,
                groovyFiles,
                scalaFiles,
                scalaJars == null ? List.of() : scalaJars,
                optimizeImports,
                importOrder,
                removeUnusedImports,
                indexFiles,
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

    private static final String TRAIN_GROOVY = """
            package demo

            class Hello {
                static void main(String[] args) {
                    println 'jk-formatter aot train'
                }
            }
            """;

    private static final String TRAIN_SCALA = """
            package demo

            object Hello {
              def main(args: Array[String]): Unit =
                println("jk-formatter aot train")
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
    static @Nullable String configKey(
            Path cache,
            @Nullable String javaStyle,
            @Nullable String kotlinStyle,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
            List<Path> indexFiles) {
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
                            SCALAFMT_VERSION,
                            indexFiles,
                            PluginJar.FORMATTER.locate(JkStores.storeCas()))
                    .digest();
        } catch (Exception e) {
            return null;
        }
    }
}
