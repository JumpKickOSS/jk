// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.KotlincDriver;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.compile.KotlincResult;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TomlValues;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.buildlogic.BuildLogicAnchor;
import cc.jumpkick.plugin.buildlogic.BuildLogicContext;
import cc.jumpkick.plugin.buildlogic.BuildLogicContributor;
import cc.jumpkick.plugin.buildlogic.BuildLogicGraph;
import cc.jumpkick.plugin.buildlogic.BuildLogicTask;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.tomlj.TomlTable;

/**
 * Project-local <strong>build logic</strong> (tickets 1037 / 1039 / 1044). Convention directory is
 * {@code .jk-build/}; override with {@code [build].logic}.
 *
 * <p>Supports:
 *
 * <ul>
 *   <li><strong>Scripts</strong> — top-level {@code before-compile.groovy} / {@code .kts} (and
 *       sibling stems); Groovy via reflective shell, Kotlin via {@code kotlinc -script}; scripts-only
 *       trees need no compiled sources
 *   <li><strong>Compiled Java / Kotlin</strong> — {@code .java} and {@code .kt} under the logic tree
 *       (typically {@code .jk-build/src/...}); Kotlin uses the product kotlinc worker
 *   <li><strong>SPI</strong> — classes implementing {@link BuildLogicContributor} register named
 *       tasks at {@link BuildLogicAnchor}s
 *   <li><strong>Legacy mains</strong> — {@code *Build} / {@code *BuildMain} with {@code main}
 *       run at {@link BuildLogicAnchor#AFTER_RESOURCES}
 * </ul>
 */
public final class BuildLogicSupport {

    /** Default project-relative directory for build logic sources (dot-dir: not product noise). */
    public static final String DEFAULT_DIR = ".jk-build";

    private BuildLogicSupport() {}

    public record Config(Path logicDir, String mainClass) {}

    /**
     * Resolve build-logic config: {@code [build].logic} overrides the directory (default {@link
     * #DEFAULT_DIR}); absent dir → empty. {@code logic = "off"} / {@code "false"} / {@code "none"}
     * disables even when {@code .jk-build/} exists.
     */
    public static Optional<Config> config(Path projectDir) {
        Path root = projectDir.toAbsolutePath().normalize();
        Path toml = projectDir.resolve("jk.toml");
        String logicRel = DEFAULT_DIR;
        String main = null;
        Optional<TomlTable> build = TomlValues.parse(toml).map(t -> t.getTable("build"));
        if (build.isPresent() && build.get() != null) {
            TomlTable b = build.get();
            String logic = b.getString("logic");
            if (logic != null && !logic.isBlank()) {
                String n = logic.trim().toLowerCase(Locale.ROOT);
                if (n.equals("off") || n.equals("false") || n.equals("none") || n.equals("disable")) {
                    return Optional.empty();
                }
                logicRel = logic.trim();
            }
            String lm = b.getString("logic-main");
            if (lm != null && !lm.isBlank()) main = lm.trim();
        }

        Path logicDir = root.resolve(logicRel).normalize();
        if (!logicDir.startsWith(root)) {
            throw new IllegalStateException("[build].logic must stay under the project root: " + logicRel);
        }
        if (!Files.isDirectory(logicDir)) return Optional.empty();
        return Optional.of(new Config(logicDir, main));
    }

    /**
     * As {@link #run(Path, BuildLayout, ActionCache, Path, BuildLogicAnchor, java.util.function.Consumer,
     * java.util.concurrent.atomic.AtomicReference)}, with no cross-anchor token cache — this call
     * computes its own if it needs one. Fine for a single anchor; BuildPlanner uses the other
     * overload to share one computation across a module's (up to four) anchor calls (JK-1655).
     */
    public static boolean run(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            Path classesDir,
            BuildLogicAnchor anchor,
            java.util.function.Consumer<String> label)
            throws IOException, InterruptedException {
        return run(
                projectDir,
                layout,
                actionCache,
                classesDir,
                anchor,
                label,
                new java.util.concurrent.atomic.AtomicReference<>());
    }

    /**
     * Compile + run build logic tasks for {@code anchor} (or restore from action cache), merging
     * outputs into {@code classesDir}. Returns whether any logic is configured for this project
     * (even if this anchor has zero tasks).
     *
     * <p>{@code inputTokensRef} caches {@link #projectInputTokens} across the (up to four) anchor
     * calls one module's build makes: computed once by whichever anchor needs it first, reused by
     * the rest — the anchors are DAG-serialized for one module (compile can't run before generate,
     * etc.), so a plain lazy-init race (matching {@code BuildPlanner}'s other per-build caches) is
     * enough; no synchronization needed. Caller owns the reference's lifetime — one per module per
     * build, never reused across builds.
     */
    public static boolean run(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            Path classesDir,
            BuildLogicAnchor anchor,
            java.util.function.Consumer<String> label,
            java.util.concurrent.atomic.AtomicReference<List<String>> inputTokensRef)
            throws IOException, InterruptedException {
        Optional<Config> cfg = config(projectDir);
        if (cfg.isEmpty()) return false;
        Config c = cfg.get();

        List<Path> javaSources = listJava(c.logicDir());
        List<Path> ktSources = listKotlin(c.logicDir());
        List<BuildLogicScripts.ScriptTask> scripts = BuildLogicScripts.discover(c.logicDir());
        if (javaSources.isEmpty() && ktSources.isEmpty() && scripts.isEmpty()) {
            if (anchor == BuildLogicAnchor.AFTER_RESOURCES) {
                label.accept(
                        "build-logic: no sources/scripts in " + c.logicDir().getFileName());
            }
            return true;
        }

        Map<BuildLogicAnchor, List<RegisteredTask>> byAnchor;
        Path kotlinStdlib = null;
        // The loader must outlive discovery: a task body first-touches classes (a helper in
        // .jk-build/src, a kotlin.collections type, a lambda class in the stdlib jar) long after
        // registration, and a closed URLClassLoader can define none of them (JK-1604). run() owns
        // the lifetime and closes it once every task for this anchor has run.
        URLClassLoader logicLoader = null;
        try {
            if (!javaSources.isEmpty() || !ktSources.isEmpty()) {
                Path logicClasses = layout.generatedSourcesDir("jk-build-classes");
                Path apiCp = apiClasspath();
                // BuildPlanner calls run() once per anchor — four times per module per build — and
                // this used to delete and recompile the whole logic tree every time, re-parsing
                // jk.toml and re-resolving the Kotlin toolchain with it. Three of the four produce
                // classes for anchors that register nothing. A stamp beside the classes makes the
                // compile happen once per change instead (JK-1606).
                String stamp = logicStamp(c.logicDir(), javaSources, ktSources, apiCp);
                Compiled compiled = readStamp(logicClasses);
                if (compiled != null && compiled.stamp().equals(stamp)) {
                    kotlinStdlib = compiled.kotlinStdlib();
                } else {
                    deleteContents(logicClasses);
                    Files.createDirectories(logicClasses);
                    if (!javaSources.isEmpty()) {
                        compileJava(javaSources, logicClasses, apiCp);
                    }
                    if (!ktSources.isEmpty()) {
                        kotlinStdlib = compileKotlin(ktSources, logicClasses, apiCp, projectDir, actionCache);
                    }
                    writeStamp(logicClasses, stamp, kotlinStdlib);
                }
                logicLoader = new URLClassLoader(
                        toUrls(logicClasses, apiCp, kotlinStdlib), BuildLogicContributor.class.getClassLoader());
                byAnchor = discoverTasks(
                        c, logicClasses, apiCp, kotlinStdlib, /* allowEmpty */ !scripts.isEmpty(), logicLoader);
            } else {
                byAnchor = emptyByAnchor();
            }
            registerScripts(byAnchor, scripts);
            return runAnchor(
                    projectDir,
                    layout,
                    actionCache,
                    classesDir,
                    anchor,
                    label,
                    c,
                    javaSources,
                    ktSources,
                    scripts,
                    byAnchor,
                    inputTokensRef);
        } finally {
            if (logicLoader != null) logicLoader.close();
        }
    }

    /** Run (or restore) every task registered at {@code anchor}. */
    private static boolean runAnchor(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            Path classesDir,
            BuildLogicAnchor anchor,
            java.util.function.Consumer<String> label,
            Config c,
            List<Path> javaSources,
            List<Path> ktSources,
            List<BuildLogicScripts.ScriptTask> scripts,
            Map<BuildLogicAnchor, List<RegisteredTask>> byAnchor,
            java.util.concurrent.atomic.AtomicReference<List<String>> inputTokensRef)
            throws IOException, InterruptedException {

        List<RegisteredTask> tasks = byAnchor.getOrDefault(anchor, List.of());
        if (tasks.isEmpty()) return true;

        List<String> sourceTokens = new ArrayList<>();
        sourceTokens.add("dir:" + projectDir.relativize(c.logicDir()));
        for (Path src : javaSources) {
            sourceTokens.add("src:" + c.logicDir().relativize(src) + ":" + Hashing.sha256Hex(Files.readAllBytes(src)));
        }
        for (Path src : ktSources) {
            sourceTokens.add("kt:" + c.logicDir().relativize(src) + ":" + Hashing.sha256Hex(Files.readAllBytes(src)));
        }
        for (BuildLogicScripts.ScriptTask s : scripts) {
            sourceTokens.add("script:" + c.logicDir().relativize(s.file()) + ":"
                    + Hashing.sha256Hex(Files.readAllBytes(s.file())));
        }
        sourceTokens.add("anchor:" + anchor.name());
        // A build-logic task reads the project, not only itself: BuildLogicContext hands it
        // projectDir and classesDir. Keying on the logic sources alone made an edit to the
        // product invisible, so the task reported `cache hit` and replayed a stale output —
        // the shipped line-count example re-merged the old count into the jar (JK-1603).
        //
        // Memoized in inputTokensRef: the source tree can't change mid-build, so whichever anchor
        // needs this first computes it and every later anchor in the same build reuses it instead
        // of re-walking/re-hashing the same tree (JK-1655).
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
        // full-compile sweep moments later (JK-1602).
        boolean generatesSources = anchor == BuildLogicAnchor.BEFORE_COMPILE;
        for (RegisteredTask task : tasks) {
            String simple = task.name();
            Path outDir = generatesSources
                    ? generatedSourceRoot(layout).resolve(simple)
                    : layout.generatedSourcesDir("jk-build-out-" + simple);
            Files.createDirectories(outDir);
            String taskId = ActionKey.qualifiedTaskId("build-logic-" + simple, projectDir);
            List<String> tokens = new ArrayList<>(sourceTokens);
            tokens.add("task:" + simple);
            tokens.add("kind:" + task.kind());
            String key = ActionKey.forArtifact(taskId, BuildIdentity.cacheKeyVersion(), tokens);

            boolean useCache = !cc.jumpkick.config.SessionContext.current()
                            .config()
                            .forceOr(false)
                    && !cc.jumpkick.config.SessionContext.current().config().rebuildOr(false);
            Optional<ActionCache.ActionRecord> hit = useCache ? actionCache.lookup(key) : Optional.empty();
            if (hit.isPresent() && !hit.get().outputs().isEmpty()) {
                deleteContents(outDir);
                Files.createDirectories(outDir);
                // A failed restore (missing/corrupt blob) falls through to the real run below.
                if (actionCache.restore(hit.get(), outDir)) {
                    label.accept("build-logic:" + simple + ": cache hit");
                    if (!generatesSources) mergeIntoClasses(outDir, classesDir);
                    continue;
                }
            }

            label.accept("build-logic:" + simple + ": " + anchor.name().toLowerCase(Locale.ROOT));
            deleteContents(outDir);
            Files.createDirectories(outDir);
            // No classesDir binding: outDir is the only surface the action cache captures,
            // so it is the only place a task may write (JK-1614).
            BuildLogicContext ctx = new BuildLogicContext(
                    projectDir.toAbsolutePath().normalize(),
                    outDir.toAbsolutePath().normalize());
            try {
                task.task().run(ctx);
            } catch (Exception e) {
                if (e instanceof InterruptedException ie) throw ie;
                if (e instanceof IOException ioe) throw ioe;
                throw new IllegalStateException("[build] logic task " + simple + " failed: " + e.getMessage(), e);
            }
            actionCache.store(taskId, key, java.util.Map.of("build-logic", key), outDir);
            if (!generatesSources) mergeIntoClasses(outDir, classesDir);
        }
        return true;
    }

    /** Back-compat: run {@link BuildLogicAnchor#AFTER_RESOURCES} only. */
    public static boolean run(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            Path classesDir,
            java.util.function.Consumer<String> label)
            throws IOException, InterruptedException {
        return run(projectDir, layout, actionCache, classesDir, BuildLogicAnchor.AFTER_RESOURCES, label);
    }

    private record RegisteredTask(String name, String kind, BuildLogicTask task) {}

    private static Map<BuildLogicAnchor, List<RegisteredTask>> emptyByAnchor() {
        Map<BuildLogicAnchor, List<RegisteredTask>> out = new EnumMap<>(BuildLogicAnchor.class);
        for (BuildLogicAnchor a : BuildLogicAnchor.values()) {
            out.put(a, new ArrayList<>());
        }
        return out;
    }

    private static void registerScripts(
            Map<BuildLogicAnchor, List<RegisteredTask>> byAnchor, List<BuildLogicScripts.ScriptTask> scripts) {
        Map<String, BuildLogicAnchor> claimed = new LinkedHashMap<>();
        for (List<RegisteredTask> list : byAnchor.values()) {
            for (RegisteredTask t : list) {
                claimed.put(t.name(), null);
            }
        }
        for (BuildLogicScripts.ScriptTask s : scripts) {
            if (claimed.containsKey(s.name())) {
                throw new IllegalStateException("duplicate build-logic task name: " + s.name() + " (script "
                        + s.file().getFileName() + ")");
            }
            claimed.put(s.name(), s.anchor());
            Path scriptFile = s.file();
            BuildLogicScripts.ScriptKind kind = s.kind();
            BuildLogicTask task = ctx -> {
                try {
                    if (kind == BuildLogicScripts.ScriptKind.KTS) {
                        BuildLogicKtsHost.evaluate(scriptFile, ctx.projectDir(), ctx.outDir());
                    } else {
                        BuildLogicGroovyHost.evaluate(scriptFile, ctx.projectDir(), ctx.outDir());
                    }
                } catch (Exception e) {
                    Throwable root = e;
                    while (root instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null) {
                        root = ite.getCause();
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

    private static Map<BuildLogicAnchor, List<RegisteredTask>> discoverTasks(
            Config c, Path logicClasses, Path apiCp, Path kotlinStdlib, boolean allowEmpty, URLClassLoader cl)
            throws IOException {
        Map<BuildLogicAnchor, List<RegisteredTask>> out = emptyByAnchor();

        // Graph collector
        Map<String, BuildLogicAnchor> nameAnchors = new LinkedHashMap<>();
        BuildLogicGraph graph = (name, anchor, task) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("build-logic task name must be non-blank");
            }
            String n = name.trim();
            if (nameAnchors.containsKey(n)) {
                throw new IllegalStateException("duplicate build-logic task name: " + n);
            }
            nameAnchors.put(n, anchor);
            out.get(anchor).add(new RegisteredTask(n, "spi", task));
        };

        {
            // SPI contributors
            for (String binary : listClassNames(logicClasses)) {
                Class<?> clazz;
                try {
                    clazz = Class.forName(binary, false, cl);
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    continue;
                }
                if (clazz.isInterface() || clazz.isEnum() || !BuildLogicContributor.class.isAssignableFrom(clazz)) {
                    continue;
                }
                try {
                    Object inst = clazz.getDeclaredConstructor().newInstance();
                    ((BuildLogicContributor) inst).register(graph);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                            "[build] logic SPI " + binary + " failed to construct/register: " + e.getMessage(), e);
                }
            }

            // Legacy mains at AFTER_RESOURCES (unless logic-main pins one class)
            List<String> mains;
            if (c.mainClass() != null && !c.mainClass().isBlank()) {
                mains = List.of(c.mainClass().trim());
            } else {
                mains = discoverLegacyMains(logicClasses, cl);
            }
            for (String main : mains) {
                // Skip if the same class already registered via SPI (contributor implements both)
                String simple = simpleName(main);
                if (nameAnchors.containsKey(simple) || nameAnchors.containsKey(main)) continue;
                String name = simple;
                final String mainClass = main;
                final Path stdlib = kotlinStdlib;
                BuildLogicTask task = ctx -> {
                    int exit = runMain(logicClasses, apiCp, stdlib, mainClass, ctx.projectDir(), ctx.outDir());
                    if (exit != 0) {
                        throw new IllegalStateException("[build] logic " + mainClass + " exited " + exit);
                    }
                };
                nameAnchors.put(name, BuildLogicAnchor.AFTER_RESOURCES);
                out.get(BuildLogicAnchor.AFTER_RESOURCES).add(new RegisteredTask(name, "main", task));
            }
        }

        if (nameAnchors.isEmpty() && !allowEmpty) {
            throw new IllegalStateException("[build] logic has no tasks — add a stem script "
                    + "(e.g. before-compile.groovy / before-compile.kts), implement "
                    + BuildLogicContributor.class.getName()
                    + " in .java/.kt, or provide a *Build / *BuildMain with public static void main"
                    + " (or set [build].logic-main)");
        }
        return out;
    }

    private static List<String> discoverLegacyMains(Path classes, ClassLoader cl) throws IOException {
        List<String> names = listClassNames(classes);
        List<String> builds = names.stream()
                .filter(n -> n.endsWith("BuildMain") || n.equals("BuildMain") || n.endsWith("Build"))
                .filter(n -> hasMain(n, cl) && !isContributor(n, cl))
                .toList();
        if (!builds.isEmpty()) return builds;
        List<String> alts = names.stream()
                .filter(n -> n.endsWith("Logic") || n.endsWith("Generator"))
                .filter(n -> hasMain(n, cl) && !isContributor(n, cl))
                .toList();
        if (!alts.isEmpty()) return alts;
        return names.stream().filter(n -> hasMain(n, cl) && !isContributor(n, cl)).findFirst().stream()
                .toList();
    }

    private static boolean isContributor(String binary, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(binary, false, cl);
            return BuildLogicContributor.class.isAssignableFrom(c) && !c.isInterface();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }

    private static boolean hasMain(String binary, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(binary, false, cl);
            c.getMethod("main", String[].class);
            return true;
        } catch (ReflectiveOperationException | NoClassDefFoundError e) {
            return false;
        }
    }

    private static List<String> listClassNames(Path classes) throws IOException {
        try (Stream<Path> s = Files.walk(classes)) {
            return s.filter(p -> p.toString().endsWith(".class")
                            && !p.getFileName().toString().contains("$"))
                    .map(p -> classes.relativize(p)
                            .toString()
                            .replace('/', '.')
                            .replace('\\', '.')
                            .replaceAll("\\.class$", ""))
                    .sorted()
                    .toList();
        }
    }

    private static String simpleName(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? binaryName : binaryName.substring(dot + 1);
    }

    private static List<Path> listJava(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    /**
     * Compiled Kotlin sources under the logic tree. Excludes {@code .kts} (script stems are a
     * separate path — {@code "foo.kts".endsWith(".kt")} is true in Java).
     */
    private static List<Path> listKotlin(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".kt") && !name.endsWith(".kts")) {
                    out.add(p);
                }
            });
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    /** What a completed build-logic compile left behind, as recorded beside the classes. */
    private record Compiled(String stamp, Path kotlinStdlib) {}

    /** Marker naming the sources the classes in this directory were built from. */
    private static final String STAMP_FILE = ".jk-logic-stamp";

    /** Identity of one build-logic compile: every source's content, plus the API classpath. */
    private static String logicStamp(Path logicDir, List<Path> javaSources, List<Path> ktSources, Path apiCp)
            throws IOException {
        List<String> tokens = new ArrayList<>();
        for (Path src : javaSources) {
            tokens.add("j:" + logicDir.relativize(src) + ":" + Hashing.sha256Hex(Files.readAllBytes(src)));
        }
        for (Path src : ktSources) {
            tokens.add("k:" + logicDir.relativize(src) + ":" + Hashing.sha256Hex(Files.readAllBytes(src)));
        }
        java.util.Collections.sort(tokens);
        tokens.add("api:" + (apiCp == null ? "" : apiCp));
        tokens.add("v:" + BuildIdentity.cacheKeyVersion());
        return Hashing.sha256Hex(String.join("\n", tokens).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Compiled readStamp(Path logicClasses) {
        Path file = logicClasses.resolve(STAMP_FILE);
        try {
            if (!Files.isRegularFile(file)) return null;
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty() || lines.get(0).isBlank()) return null;
            Path stdlib = lines.size() > 1 && !lines.get(1).isBlank() ? Path.of(lines.get(1)) : null;
            // A recorded stdlib that has since been swept from the store means recompile.
            if (stdlib != null && !Files.exists(stdlib)) return null;
            return new Compiled(lines.get(0), stdlib);
        } catch (IOException | RuntimeException e) {
            return null; // unreadable stamp is a miss, never a failure
        }
    }

    private static void writeStamp(Path logicClasses, String stamp, Path kotlinStdlib) throws IOException {
        Files.writeString(logicClasses.resolve(STAMP_FILE), stamp + "\n" + (kotlinStdlib == null ? "" : kotlinStdlib));
    }

    private static void compileJava(List<Path> sources, Path classes, Path apiCp) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("[build] logic: no system javac");
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(classes.toString());
        if (apiCp != null && Files.exists(apiCp)) {
            args.add("-cp");
            args.add(apiCp.toString());
        }
        for (Path s : sources) args.add(s.toString());
        int rc = javac.run(null, null, null, args.toArray(String[]::new));
        if (rc != 0) throw new IllegalStateException("[build] logic: javac failed (exit " + rc + ")");
    }

    /**
     * Compile {@code .kt} build-logic sources into {@code classes}. Returns the kotlin-stdlib jar
     * (must ride the SPI/main classpath). Uses the product kotlin-compiler worker + default Kotlin
     * version; non-incremental (build-logic trees are small).
     */
    private static Path compileKotlin(
            List<Path> sources, Path classes, Path apiCp, Path projectDir, ActionCache actionCache)
            throws IOException, InterruptedException {
        JkBuild project;
        try {
            project = JkBuildParser.parse(projectDir.resolve("jk.toml"));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[build] logic: cannot parse jk.toml for Kotlin compile: " + e.getMessage(), e);
        }
        RepoGroup repos = RepoGroupBuilder.buildFor(project, null, actionCache.cas());
        KotlinPluginSetup.Prepared prep;
        try {
            prep = KotlinPluginSetup.prepare(repos, actionCache.cas(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        List<Path> compileCp = new ArrayList<>();
        if (apiCp != null && Files.exists(apiCp)) {
            compileCp.add(apiCp);
        }
        // Already-compiled Java build-logic (if any) so Kotlin can call it. Do not put an empty
        // output dir on the classpath (and avoid self-output-as-input when Kotlin-only).
        if (hasClassFiles(classes)) {
            compileCp.add(classes);
        }
        compileCp.add(prep.stdlib());

        int jvmTarget = CompileSupport.kotlinJvmTarget(Runtime.version().feature());
        KotlincRequest req = KotlincRequest.builder()
                .sources(sources)
                .classpath(compileCp)
                .outputDir(classes)
                .jvmTarget(jvmTarget)
                .workerClasspath(prep.workerClasspath())
                .javaHome(JavaHomes.runningJavaHome())
                .workingDir(null) // non-incremental (build-logic trees are small)
                .extraArgs(List.of("-no-stdlib"))
                .moduleName("jk-build-logic")
                .build();
        KotlincResult result = new KotlincDriver().compile(req);
        if (!result.success()) {
            String out = result.output() == null ? "" : result.output().strip();
            throw new IllegalStateException("[build] logic: kotlinc failed" + (out.isEmpty() ? "" : ":\n" + out));
        }
        return prep.stdlib();
    }

    /** Location of the plugin-sdk jar / classes dir that hosts the build-logic API. */
    static Path apiClasspath() {
        try {
            var pd = BuildLogicContributor.class.getProtectionDomain();
            var cs = pd == null ? null : pd.getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                throw new IllegalStateException(
                        "[build] logic: build-logic API classpath unknown (null code source — exotic packaging)");
            }
            return Path.of(cs.getLocation().toURI());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("[build] logic: cannot locate build-logic API classpath", e);
        }
    }

    private static URL[] toUrls(Path logicClasses, Path apiCp, Path kotlinStdlib) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(logicClasses.toUri().toURL());
        if (apiCp != null && Files.exists(apiCp)) {
            urls.add(apiCp.toUri().toURL());
        }
        if (kotlinStdlib != null && Files.exists(kotlinStdlib)) {
            urls.add(kotlinStdlib.toUri().toURL());
        }
        return urls.toArray(URL[]::new);
    }

    private static int runMain(Path classes, Path apiCp, Path kotlinStdlib, String main, Path projectDir, Path outDir)
            throws IOException, InterruptedException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String sep = java.io.File.pathSeparator;
        StringBuilder cp = new StringBuilder(classes.toString());
        if (apiCp != null && Files.exists(apiCp)) {
            cp.append(sep).append(apiCp);
        }
        if (kotlinStdlib != null && Files.exists(kotlinStdlib)) {
            cp.append(sep).append(kotlinStdlib);
        }
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", cp.toString(), main, "--project", projectDir.toString(), "--out", outDir.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String log = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0 && !log.isBlank()) System.err.println(log);
        return exit;
    }

    /**
     * What a build-logic task can read through {@link BuildLogicContext}, as cache-key tokens: the
     * module's source roots plus {@code jk.toml}/{@code jk-lock.toml} — {@code BuildLogicContext}
     * hands a task {@code projectDir} itself, and reading its own project file (e.g. to embed the
     * declared version) is the obvious first thing a codegen task does with that (JK-1603).
     *
     * <p>Conservative on purpose — a task declares no inputs, so the key covers every input it
     * <em>could</em> consume. Narrowing it needs a declared-input surface on the SPI.
     *
     * <p>The key is the <strong>sources</strong>, not {@code classesDir}, even for the anchors that
     * read classes. Classes are a function of these sources, and hashing the classes tree would be
     * self-referential: post-compile anchors merge their own output into it, so every run would
     * perturb its own next key and a cache hit could never happen.
     */
    /** Test seam: counts real {@link #projectInputTokens} computations (JK-1655's "at most once per build" claim). */
    static final java.util.concurrent.atomic.AtomicInteger PROJECT_INPUT_TOKENS_CALLS_FOR_TESTS =
            new java.util.concurrent.atomic.AtomicInteger();

    private static List<String> projectInputTokens(Path projectDir) throws IOException {
        PROJECT_INPUT_TOKENS_CALLS_FOR_TESTS.incrementAndGet();
        List<String> tokens = new ArrayList<>();
        for (Path dir : cc.jumpkick.layout.ModuleLayout.fingerprintDirs(projectDir, /* skipTests */ false)) {
            hashTree(projectDir, dir, "in", tokens);
        }
        for (String file : new String[] {"jk.toml", "jk-lock.toml"}) {
            Path p = projectDir.resolve(file);
            if (Files.isRegularFile(p)) {
                tokens.add("in:" + file + ":" + Hashing.sha256Hex(Files.readAllBytes(p)));
            }
        }
        java.util.Collections.sort(tokens);
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
        return layout.generatedSourcesDir("jk-build");
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
        if (!Files.isDirectory(generated)) return;
        Files.createDirectories(classesDir);
        try (Stream<Path> s = Files.walk(generated)) {
            for (Path file : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(file)) continue;
                Path dest = classesDir.resolve(generated.relativize(file).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteContents(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(dir)) Files.deleteIfExists(p);
            }
        }
    }

    private static boolean hasClassFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> s = Files.walk(dir)) {
            return s.anyMatch(p -> p.toString().endsWith(".class"));
        }
    }
}
