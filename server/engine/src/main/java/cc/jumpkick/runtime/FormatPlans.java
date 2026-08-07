// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.plugin.protocol.Jsonl;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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
            Path rewriteConfig,
            FileObserver observer) {
        BuildPlanKey<List> javaFilesKey = BuildPlanKey.of("format-java-files", List.class);
        BuildPlanKey<List> kotlinFilesKey = BuildPlanKey.of("format-kotlin-files", List.class);
        BuildPlanKey<List> javaJarsKey = BuildPlanKey.of("format-java-jars", List.class);
        BuildPlanKey<List> kotlinJarsKey = BuildPlanKey.of("format-kotlin-jars", List.class);

        Task collect = Task.builder(TaskNames.COLLECT_SOURCES)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("collect sources");
                    List<Path> javaFiles = collectSources(projectDir, ".java");
                    List<Path> kotlinFiles = collectSources(projectDir, ".kt");
                    ctx.put(javaFilesKey, javaFiles);
                    ctx.put(kotlinFilesKey, kotlinFiles);
                    ctx.put(TOTAL, javaFiles.size() + kotlinFiles.size());
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
                        ctx.put(kotlinJarsKey, List.of());
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("resolve formatter jars");
                    var resolver = ToolResolver.mavenCentral(new Http(), JkStores.cas(cache));
                    try {
                        ctx.put(
                                javaJarsKey,
                                javaFiles.isEmpty()
                                        ? List.of()
                                        : resolver.resolve(javaCoord(javaStyle), "java-format", "ignored")
                                                .classpath());
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
                    int total = javaFiles.size() + kotlinFiles.size();
                    if (total == 0) {
                        ctx.put(CHANGED, 0);
                        ctx.put(CLEAN, 0);
                        ctx.put(ERRORS, 0);
                        ctx.put(WORKER_EXIT, 0);
                        return;
                    }
                    ctx.updateTicks(total);
                    ctx.label(check ? "check formatting" : "format sources");
                    @SuppressWarnings("unchecked")
                    List<Path> javaJars = (List<Path>) ctx.require(javaJarsKey);
                    @SuppressWarnings("unchecked")
                    List<Path> kotlinJars = (List<Path>) ctx.require(kotlinJarsKey);

                    Path workerJar = PluginJar.FORMATTER.locate(JkStores.cas(cache));
                    Path spec = writeSpec(
                            check,
                            javaStyle,
                            kotlinStyle,
                            javaFiles,
                            javaJars,
                            kotlinFiles,
                            kotlinJars,
                            optimizeImports,
                            rewriteConfig,
                            cache);
                    try {
                        AtomicInteger changed = new AtomicInteger();
                        AtomicInteger clean = new AtomicInteger();
                        AtomicInteger errors = new AtomicInteger();
                        AtomicInteger index = new AtomicInteger();
                        int exit = new PluginClient("##JKFMT:")
                                .on("file", json -> {
                                    String status = Jsonl.str(json, "status");
                                    if ("changed".equals(status)) changed.incrementAndGet();
                                    else if ("error".equals(status)) errors.incrementAndGet();
                                    else clean.incrementAndGet();
                                    observer.onFile(
                                            Jsonl.str(json, "path"),
                                            status,
                                            Jsonl.str(json, "msg"),
                                            index.incrementAndGet(),
                                            total);
                                    ctx.progress(1);
                                })
                                .passthrough(ctx::output)
                                .run(PluginLaunch.javaCommand(
                                        workerJar, javaFiles.isEmpty() ? List.of() : JAVAC_EXPORTS, spec));
                        ctx.put(CHANGED, changed.get());
                        ctx.put(CLEAN, clean.get());
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
            List<Path> kotlinFiles,
            List<Path> kotlinJars,
            boolean optimizeImports,
            Path rewriteConfig,
            Path cacheDir)
            throws IOException {
        SpecWriter w = new SpecWriter()
                .op(PluginProtocol.OP_COMMAND, "format", "jk-formatter")
                .configBool("apply", !check);
        if (!javaFiles.isEmpty()) {
            w.configString("javaStyle", javaStyle)
                    .configString("javaVersion", javaVersion(javaStyle))
                    .configList("javaJars", absPaths(javaJars))
                    .configList("javaFiles", absPaths(javaFiles));
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
        Path spec = Files.createTempFile("jk-format-", ".spec");
        Files.write(spec, w.lines(), StandardCharsets.UTF_8);
        return spec;
    }

    private static List<String> absPaths(List<Path> paths) {
        return paths.stream().map(p -> p.toAbsolutePath().toString()).toList();
    }

    /** Collect project source files with the given extension, skipping build/VCS output dirs. */
    private static List<Path> collectSources(Path root, String ext) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(ext))
                    .filter(FormatPlans::notExcluded)
                    .sorted()
                    .toList();
        }
    }

    static boolean notExcluded(Path p) {
        for (Path seg : p) {
            String s = seg.toString();
            if (s.equals("target") || s.equals("build") || s.equals(".jk") || s.equals(".git") || s.equals("node_modules")) {
                return false;
            }
            // A giter8 template root has an unambiguous shape — a directory literally suffixed
            // `.g8`, or named `g8` (the jk-templates catalog and this repo's own templates/*.g8
            // dogfood tree both use one of these two). A bare "templates"/"giter8" segment name is
            // NOT a reliable signal: this repo's own cc.jumpkick.templates package (and any user's
            // legitimate templates/ code, e.g. email templates) would be silently unformatted too.
            if (s.endsWith(".g8") || s.equals("g8")) {
                return false;
            }
            // Giter8 placeholders are wrapped in `$…$` ($package$, $name$) — not just any name that
            // happens to contain a `$`.
            if (s.length() > 1 && s.startsWith("$") && s.endsWith("$")) {
                return false;
            }
        }
        return true;
    }
}
