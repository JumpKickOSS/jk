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
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jsonl.Jsonl;
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
 * ({@code --check} non-zero is not a plan failure).
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

    /** Receives each file's result as the plugin streams it ({@code status} = {@code changed}/{@code clean}/{@code error}). */
    public interface FileObserver {
        void onFile(String path, String status, String message, int index, int total);
    }

    /** Summary counts, populated by the format step (all present once the plan finishes successfully). */
    public static final BuildPlanKey<Integer> CHANGED = BuildPlanKey.of("format-changed", Integer.class);

    public static final BuildPlanKey<Integer> CLEAN = BuildPlanKey.of("format-clean", Integer.class);
    public static final BuildPlanKey<Integer> ERRORS = BuildPlanKey.of("format-errors", Integer.class);
    public static final BuildPlanKey<Integer> TOTAL = BuildPlanKey.of("format-total", Integer.class);
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

        BuildPlanKey<FormatFreshnessIndex> indexKey = BuildPlanKey.of("format-index", FormatFreshnessIndex.class);

        Task collect = Task.builder(TaskNames.COLLECT_SOURCES)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("collect sources");
                    CollectedSources all = collectSources(projectDir);
                    FormatFreshnessIndex index = openIndex(
                            projectDir,
                            cache,
                            javaStyle,
                            kotlinStyle,
                            optimizeImports,
                            importOrder,
                            removeUnusedImports,
                            rewriteConfig);
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
                            cache,
                            null);
                    try {
                        AtomicInteger changed = new AtomicInteger();
                        AtomicInteger clean = new AtomicInteger();
                        AtomicInteger errors = new AtomicInteger();
                        AtomicInteger index = new AtomicInteger();
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
                        int exit = new PluginClient("##JKFMT:")
                                .on("file", json -> {
                                    String status = Jsonl.str(json, "status");
                                    String path = Jsonl.str(json, "path");
                                    if ("changed".equals(status)) {
                                        changed.incrementAndGet();
                                        if (!check && freshness != null) freshness.record(Path.of(path));
                                    } else if ("error".equals(status)) {
                                        errors.incrementAndGet();
                                    } else {
                                        clean.incrementAndGet();
                                        if (freshness != null) freshness.record(Path.of(path));
                                    }
                                    observer.onFile(
                                            path, status, Jsonl.str(json, "msg"), index.incrementAndGet(), total);
                                    ctx.progress(1);
                                })
                                .passthrough(ctx::output)
                                .run(PluginLaunch.javaCommand(workerJar, extra, spec));
                        if (freshness != null) freshness.save();
                        ctx.put(CHANGED, changed.get());
                        ctx.put(CLEAN, preClean + clean.get());
                        ctx.put(ERRORS, errors.get());
                        ctx.put(WORKER_EXIT, exit);
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

    private static Path writeSpec(
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
            Path cacheDir,
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
            if (rewriteConfig != null)
                w.configString(
                        "rewriteConfigFile", rewriteConfig.toAbsolutePath().toString());
        }
        // Pass the cache root so the plugin can read/write per-file format stamps.
        if (cacheDir != null)
            w.configString("cacheDir", cacheDir.toAbsolutePath().toString());
        Path spec = dest != null ? dest : Files.createTempFile("jk-format-", ".spec");
        if (dest != null && dest.getParent() != null) Files.createDirectories(dest.getParent());
        Files.write(spec, w.lines(), StandardCharsets.UTF_8);
        return spec;
    }

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
                scratch,
                scratch.resolve("train.spec"));
        List<String> jvmFlags = new ArrayList<>();
        jvmFlags.add("-XX:AOTCacheOutput=" + aotOutput);
        jvmFlags.addAll(JvmOptions.batchFlags(1));
        if (!javaFiles.isEmpty()) jvmFlags.addAll(JAVAC_EXPORTS);
        boolean win = HostPlatform.isWindows();
        Path javaExe = hostJavaHome.resolve("bin").resolve(win ? "java.exe" : "java");
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

    private static FormatFreshnessIndex openIndex(
            Path projectDir,
            Path cache,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
            Path rewriteConfig) {
        try {
            Path workerJar = PluginJar.FORMATTER.locate(JkStores.cas(cache));
            String key = FormatFreshnessIndex.configKey(
                    javaStyle,
                    javaVersion(javaStyle),
                    kotlinStyle,
                    KTFMT_VERSION,
                    optimizeImports,
                    importOrder,
                    removeUnusedImports,
                    rewriteConfig,
                    workerJar);
            return FormatFreshnessIndex.open(cache, projectDir, key);
        } catch (Exception e) {
            return FormatFreshnessIndex.disabled(projectDir);
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
        if (s.equals("target")
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
