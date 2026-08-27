// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.BuildLogicToml.Logic;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.ModuleLayoutPlugins;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Project-local <strong>build logic</strong>. Convention directories are {@code jk/} (visible,
 * wins if both exist) and {@code .jk/}; override with {@code [build].logic}.
 *
 * <p>Top-level stem scripts only ({@code before-compile.groovy} / {@code .kts} and sibling stems).
 * Groovy runs in a forked JVM; Kotlin via {@code kotlinc -script}. Compiled {@code .java}/{@code
 * .kt} under the logic tree is rejected.
 */
public final class BuildLogicSupport {

    private BuildLogicSupport() {}

    /**
     * As {@link #run(Path, BuildLayout, ActionCache, Path, BuildLogicAnchor, Consumer,
     * AtomicReference)}, with no cross-anchor token cache.
     */
    public static boolean run(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            Path classesDir,
            BuildLogicAnchor anchor,
            Consumer<String> label)
            throws IOException, InterruptedException {
        return run(projectDir, layout, actionCache, classesDir, anchor, label, new AtomicReference<>());
    }

    /**
     * Run build-logic scripts for {@code anchor} (or restore from action cache), merging outputs
     * into {@code classesDir}. Returns whether any logic is configured for this project (even if
     * this anchor has zero tasks).
     *
     * <p>{@code inputTokensRef} caches {@link #projectInputTokens} across the (up to four) anchor
     * calls one module's build makes: computed once by whichever anchor needs it first, reused by
     * the rest. Caller owns the reference's lifetime — one per module per build, never reused
     * across builds.
     */
    public static boolean run(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            Path classesDir,
            BuildLogicAnchor anchor,
            Consumer<String> label,
            AtomicReference<List<String>> inputTokensRef)
            throws IOException, InterruptedException {
        Optional<Logic> cfg = BuildLogicToml.resolve(projectDir);
        if (cfg.isEmpty()) return false;
        Logic c = cfg.get();
        rejectCompiledSources(c.dir());
        List<BuildLogicScripts.ScriptTask> scripts = BuildLogicScripts.discover(c.dir());
        if (scripts.isEmpty()) {
            if (anchor == BuildLogicAnchor.AFTER_RESOURCES) {
                label.accept("build-logic: no scripts in " + c.dir().getFileName());
            }
            return true;
        }
        Map<BuildLogicAnchor, List<RegisteredTask>> byAnchor = emptyByAnchor();
        registerScripts(byAnchor, scripts);
        return runAnchor(
                projectDir, layout, actionCache, classesDir, anchor, label, c, scripts, byAnchor, inputTokensRef);
    }

    /** Run (or restore) every task registered at {@code anchor}. */
    private static boolean runAnchor(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            Path classesDir,
            BuildLogicAnchor anchor,
            Consumer<String> label,
            Logic c,
            List<BuildLogicScripts.ScriptTask> scripts,
            Map<BuildLogicAnchor, List<RegisteredTask>> byAnchor,
            AtomicReference<List<String>> inputTokensRef)
            throws IOException, InterruptedException {

        List<RegisteredTask> tasks = byAnchor.getOrDefault(anchor, List.of());
        if (tasks.isEmpty()) return true;

        List<String> sourceTokens = new ArrayList<>();
        sourceTokens.add("dir:" + projectDir.relativize(c.dir()));
        for (BuildLogicScripts.ScriptTask s : scripts) {
            sourceTokens.add(
                    "script:" + c.dir().relativize(s.file()) + ":" + Hashing.sha256Hex(Files.readAllBytes(s.file())));
        }
        sourceTokens.add("anchor:" + anchor.name());

        List<String> inputTokens = inputTokensRef.get();
        if (inputTokens == null) {
            inputTokens = projectInputTokens(projectDir);
            inputTokensRef.compareAndSet(null, inputTokens);
            inputTokens = inputTokensRef.get();
        }
        sourceTokens.addAll(inputTokens);

        // BEFORE_COMPILE is codegen: its output joins the compile source set (like KSP), it is
        // never merged into classes/. Merging there compiled nothing — a generated .java was
        // packaged verbatim as a data file — and any .class it staged was deleted by javac's
        // full-compile sweep moments later.
        boolean generatesSources = anchor == BuildLogicAnchor.BEFORE_COMPILE;
        for (RegisteredTask task : tasks) {
            String simple = task.name();
            Path outDir = generatesSources
                    ? generatedSourceRoot(layout).resolve(simple)
                    : layout.generatedSourcesDir("jk-logic-out-" + simple);
            Files.createDirectories(outDir);
            String taskId = ActionKey.qualifiedTaskId("build-logic-" + simple, projectDir);
            List<String> tokens = new ArrayList<>(sourceTokens);
            tokens.add("task:" + simple);
            tokens.add("kind:" + task.kind());
            String key = ActionKey.forArtifact(taskId, BuildIdentity.cacheKeyVersion(), tokens);

            boolean useCache = !SessionContext.current().config().forceOr(false)
                    && !SessionContext.current().config().rebuildOr(false);
            Optional<ActionCache.ActionRecord> hit = useCache ? actionCache.lookup(key) : Optional.empty();
            if (hit.isPresent() && !hit.get().outputs().isEmpty()) {
                deleteContents(outDir);
                Files.createDirectories(outDir);
                if (actionCache.restore(hit.get(), outDir)) {
                    label.accept("build-logic:" + simple + ": cache hit");
                    if (!generatesSources) mergeIntoClasses(outDir, classesDir);
                    continue;
                }
            }

            label.accept("build-logic:" + simple + ": " + anchor.name().toLowerCase(Locale.ROOT));
            deleteContents(outDir);
            Files.createDirectories(outDir);
            try {
                task.run()
                        .run(
                                projectDir.toAbsolutePath().normalize(),
                                outDir.toAbsolutePath().normalize());
            } catch (Exception e) {
                if (e instanceof InterruptedException ie) throw ie;
                if (e instanceof IOException ioe) throw ioe;
                throw new IllegalStateException("[build] logic task " + simple + " failed: " + e.getMessage(), e);
            }
            actionCache.store(taskId, key, Map.of(TaskNames.BUILD_LOGIC, key), outDir);
            if (!generatesSources) mergeIntoClasses(outDir, classesDir);
        }
        return true;
    }

    /** Back-compat: run {@link BuildLogicAnchor#AFTER_RESOURCES} only. */
    public static boolean run(
            Path projectDir, BuildLayout layout, ActionCache actionCache, Path classesDir, Consumer<String> label)
            throws IOException, InterruptedException {
        return run(projectDir, layout, actionCache, classesDir, BuildLogicAnchor.AFTER_RESOURCES, label);
    }

    @FunctionalInterface
    private interface ScriptRun {
        void run(Path projectDir, Path outDir) throws Exception;
    }

    private record RegisteredTask(String name, String kind, ScriptRun run) {}

    private static Map<BuildLogicAnchor, List<RegisteredTask>> emptyByAnchor() {
        Map<BuildLogicAnchor, List<RegisteredTask>> out = new EnumMap<>(BuildLogicAnchor.class);
        for (BuildLogicAnchor a : BuildLogicAnchor.values()) {
            out.put(a, new ArrayList<>());
        }
        return out;
    }

    private static void registerScripts(
            Map<BuildLogicAnchor, List<RegisteredTask>> byAnchor, List<BuildLogicScripts.ScriptTask> scripts) {
        for (BuildLogicScripts.ScriptTask s : scripts) {
            Path scriptFile = s.file();
            BuildLogicScripts.ScriptKind kind = s.kind();
            ScriptRun task = (projectDir, outDir) -> {
                try {
                    if (kind == BuildLogicScripts.ScriptKind.KTS) {
                        BuildLogicKtsHost.evaluate(scriptFile, projectDir, outDir);
                    } else {
                        BuildLogicGroovyHost.evaluate(scriptFile, projectDir, outDir);
                    }
                } catch (Exception e) {
                    Throwable root = e;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    if (root instanceof InterruptedException ie) throw ie;
                    if (root instanceof IOException ioe) throw ioe;
                    String msg = root.getMessage() != null
                            ? root.getMessage()
                            : root.getClass().getSimpleName();
                    throw new IllegalStateException(
                            "[build] logic script " + scriptFile.getFileName() + " failed: " + msg, root);
                }
            };
            String kindLabel = kind == BuildLogicScripts.ScriptKind.KTS ? "script-kts" : "script";
            byAnchor.get(s.anchor()).add(new RegisteredTask(s.name(), kindLabel, task));
        }
    }

    /**
     * Compiled sources under {@code .jk/} are not a feature. Fail so a leftover nested project
     * cannot silently stop running.
     */
    static void rejectCompiledSources(Path logicDir) throws IOException {
        if (!Files.isDirectory(logicDir)) return;
        try (Stream<Path> walk = Files.walk(logicDir)) {
            List<Path> banned = walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".java") || (n.endsWith(".kt") && !n.endsWith(".kts"));
                    })
                    .sorted()
                    .toList();
            if (!banned.isEmpty()) {
                throw new IllegalStateException("[build] logic is stem scripts only (.groovy / .kts) — found "
                        + logicDir.relativize(banned.getFirst())
                        + " under "
                        + logicDir.getFileName()
                        + "/");
            }
        }
    }

    /**
     * What a build-logic task can read through {@code projectDir}, as cache-key tokens: the
     * module's source roots plus {@code jk.toml}/{@code jk-lock.toml}. Conservative on purpose — a
     * script declares no inputs, so the key covers every input it <em>could</em> consume.
     *
     * <p>The key is the <strong>sources</strong>, not {@code classesDir}, even for the anchors that
     * read classes. Classes are a function of these sources, and hashing the classes tree would be
     * self-referential: post-compile anchors merge their own output into it.
     */
    static final AtomicInteger PROJECT_INPUT_TOKENS_CALLS_FOR_TESTS = new AtomicInteger();

    private static List<String> projectInputTokens(Path projectDir) throws IOException {
        PROJECT_INPUT_TOKENS_CALLS_FOR_TESTS.incrementAndGet();
        List<String> tokens = new ArrayList<>();
        LinkedHashSet<Path> dirs = new LinkedHashSet<>(ModuleLayout.fingerprintDirs(projectDir, /* skipTests */ false));
        for (var root : ModuleLayoutPlugins.pluginContributedRoots(projectDir)) {
            Path p = projectDir.resolve(root.relative());
            if (Files.isDirectory(p)) dirs.add(p.toAbsolutePath().normalize());
        }
        for (Path dir : dirs) {
            hashTree(projectDir, dir, "in", tokens);
        }
        for (String file : new String[] {ManifestPaths.MANIFEST, ManifestPaths.LOCK}) {
            Path p = projectDir.resolve(file);
            if (Files.isRegularFile(p)) {
                tokens.add("in:" + file + ":" + Hashing.sha256Hex(Files.readAllBytes(p)));
            }
        }
        Collections.sort(tokens);
        return tokens;
    }

    /** Path+content tokens for one tree, relative to {@code base}; missing trees contribute none. */
    private static void hashTree(Path base, Path dir, String prefix, List<String> out) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                out.add(prefix + ":" + base.relativize(f) + ":" + Hashing.sha256Hex(Files.readAllBytes(f)));
            }
        }
    }

    /**
     * Root holding every {@link BuildLogicAnchor#BEFORE_COMPILE} task's output, one subdirectory per
     * task. The compilers read it as a generated-source root, the same way KSP output is read.
     */
    public static Path generatedSourceRoot(BuildLayout layout) {
        return layout.generatedSourcesDir("jk-logic");
    }

    /** Generated build-logic sources with {@code suffix}, in a stable order. */
    public static List<Path> generatedSources(BuildLayout layout, String suffix) throws IOException {
        Path root = generatedSourceRoot(layout);
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private static void mergeIntoClasses(Path generated, Path classesDir) throws IOException {
        PathUtil.copyTree(generated, classesDir);
    }

    private static void deleteContents(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(dir)) Files.deleteIfExists(p);
            }
        }
    }
}
