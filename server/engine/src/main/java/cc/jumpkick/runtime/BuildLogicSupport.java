// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.BuildLogicToml.Logic;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.ModuleLayoutPlugins;
import cc.jumpkick.layout.WalkSkip;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FileHashMemo;
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
import org.jspecify.annotations.Nullable;

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
            @Nullable Path classesDir,
            BuildLogicAnchor anchor,
            Consumer<String> label)
            throws IOException, InterruptedException {
        return run(projectDir, layout, actionCache, classesDir, anchor, label, new AtomicReference<>());
    }

    /**
     * A script's transcript on the output channel: one header line naming the script, then its
     * lines indented under it, so a reader of the Ctrl-O ring or a {@code -v} run knows which
     * {@code .jk/*.kts} said what. Split on any line terminator — the Kotlin host captures
     * {@code println} with the platform separator and the Groovy host reads the forked process
     * raw, so on Windows every line would otherwise keep its {@code \r}. A trailing blank from the
     * script's final newline adds nothing.
     */
    static void emitLines(String scriptFile, String captured, Consumer<String> output) {
        if (captured == null || captured.isBlank() || output == null) return;
        output.accept(scriptFile + ":");
        for (String line : captured.stripTrailing().split("\\R", -1)) {
            output.accept("  " + line);
        }
    }

    /**
     * Reject a script whose anchor does not belong to the scope it was found in.
     *
     * <p>A module has a compile to be before and a jar to be after; a workspace root has neither,
     * and a module has no "after every member" moment. Silently skipping the wrong stem is the one
     * behaviour worth ruling out — a script that does not run and does not complain is
     * indistinguishable from one that passed.
     */
    static void rejectMisplacedStems(List<BuildLogicScripts.ScriptTask> scripts, boolean workspaceRoot, Path logicDir) {
        rejectMisplacedStems(scripts, workspaceRoot, logicDir, logicDir.getParent());
    }

    static void rejectMisplacedStems(
            List<BuildLogicScripts.ScriptTask> scripts,
            boolean workspaceRoot,
            Path logicDir,
            @Nullable Path projectDir) {
        boolean member =
                projectDir != null && WorkspaceScan.findRoot(projectDir).isPresent();
        for (BuildLogicScripts.ScriptTask s : scripts) {
            if (s.anchor() == BuildLogicAnchor.GATE) {
                if (!member) continue;
                throw misplaced(
                        logicDir,
                        s,
                        "a module",
                        "before-compile / after-compile / after-resources / before-package —"
                                + " after-build and gate are the invocation root's anchors");
            }
            if (s.anchor().workspaceScoped() == workspaceRoot) continue;
            String where = workspaceRoot ? "a workspace root" : "a module";
            String use = workspaceRoot
                    ? "after-build or gate — the root has no compile or package step for the others to cut against"
                    : "before-compile / after-compile / after-resources / before-package —"
                            + " after-build and gate are the invocation root's anchors";
            throw misplaced(logicDir, s, where, use);
        }
    }

    /**
     * The one jk-authored prefix on a failed script. The body below it is the host's output
     * verbatim — its first error line already carries the script's own file:line, so
     * nothing is computed here and nothing buries it. A host-lifecycle failure ("the .kts host
     * died") already carries its own single prefix and passes through.
     */
    static IllegalStateException scriptFailure(Path scriptFile, Throwable root) {
        String msg =
                root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        if (msg.startsWith("[build] logic")) {
            return new IllegalStateException(msg, root);
        }
        return new IllegalStateException("[build] logic script " + scriptFile.getFileName() + " failed:\n" + msg, root);
    }

    /**
     * A failure that already carries the one prefix is not wrapped again — the failing task's
     * name is the step label the CLI renders, and restating it in the message only pushed the
     * script's file:line further from the reader.
     */
    static RuntimeException taskFailure(String taskName, Exception e) {
        if (e instanceof IllegalStateException ise
                && ise.getMessage() != null
                && ise.getMessage().startsWith("[build] logic")) {
            return ise;
        }
        return new IllegalStateException("[build] logic task " + taskName + " failed: " + e.getMessage(), e);
    }

    private static IllegalStateException misplaced(
            Path logicDir, BuildLogicScripts.ScriptTask s, String where, String use) {
        return new IllegalStateException("[build] " + logicDir.getFileName() + "/"
                + s.file().getFileName()
                + " is not a valid stem for "
                + where
                + ". Use "
                + use);
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
            @Nullable Path classesDir,
            BuildLogicAnchor anchor,
            Consumer<String> label,
            AtomicReference<@Nullable List<String>> inputTokensRef)
            throws IOException, InterruptedException {
        return run(projectDir, layout, actionCache, classesDir, anchor, label, line -> {}, inputTokensRef);
    }

    /**
     * As above with an {@code output} sink for whatever the scripts print.
     *
     * <p>Separate parameter rather than folded into {@code label}: a label is a one-line status the
     * live view replaces in place, and script output is a transcript that belongs above the region
     * with the compilers' and native-image's — buffered for the Ctrl-O peek ring, printed under
     * {@code -v}. Passing one for the other either overwrites a status with a log or buries a log
     * in a status.
     */
    public static boolean run(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            @Nullable Path classesDir,
            BuildLogicAnchor anchor,
            Consumer<String> label,
            Consumer<String> output,
            AtomicReference<@Nullable List<String>> inputTokensRef)
            throws IOException, InterruptedException {
        Optional<Logic> cfg = BuildLogicToml.resolve(projectDir);
        if (cfg.isEmpty()) return false;
        Logic c = cfg.get();
        rejectCompiledSources(c.dir());
        List<BuildLogicScripts.ScriptTask> scripts = BuildLogicScripts.discover(c.dir());
        rejectMisplacedStems(scripts, anchor.workspaceScoped(), c.dir(), projectDir);
        if (scripts.isEmpty()) {
            if (anchor == BuildLogicAnchor.AFTER_RESOURCES
                    || anchor == BuildLogicAnchor.AFTER_BUILD
                    || anchor == BuildLogicAnchor.GATE) {
                label.accept("build-logic: no scripts in " + c.dir().getFileName());
            }
            return true;
        }
        Map<BuildLogicAnchor, List<RegisteredTask>> byAnchor = emptyByAnchor();
        registerScripts(byAnchor, scripts);
        return runAnchor(
                projectDir,
                layout,
                actionCache,
                classesDir,
                anchor,
                label,
                output,
                c,
                scripts,
                byAnchor,
                inputTokensRef);
    }

    /** Run (or restore) every task registered at {@code anchor}. */
    private static boolean runAnchor(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            @Nullable Path classesDir,
            BuildLogicAnchor anchor,
            Consumer<String> label,
            Consumer<String> output,
            Logic c,
            List<BuildLogicScripts.ScriptTask> scripts,
            Map<BuildLogicAnchor, List<RegisteredTask>> byAnchor,
            AtomicReference<@Nullable List<String>> inputTokensRef)
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
            // The key must cover everything the script can read, and the two scopes read different
            // things: a module script sees its own module, a root script sees the whole workspace.
            // Keying a workspace-wide check on one directory's inputs would replay a stale verdict
            // the moment any other module changed.
            inputTokens = anchor.workspaceScoped() ? workspaceInputTokens(projectDir) : projectInputTokens(projectDir);
            inputTokensRef.compareAndSet(null, inputTokens);
            inputTokens = inputTokensRef.get();
        }
        sourceTokens.addAll(inputTokens);

        // BEFORE_COMPILE is codegen: its output joins the compile source set (like KSP), it is
        // never merged into classes/. Merging there compiled nothing — a generated .java was
        // packaged verbatim as a data file — and any .class it staged was deleted by javac's
        // full-compile sweep moments later.
        boolean generatesSources = anchor == BuildLogicAnchor.BEFORE_COMPILE;
        // AFTER_BUILD runs at a workspace root, which has no classes tree — there is nothing to
        // merge into and nothing downstream that would read it. Its outDir is its own output.
        boolean mergesIntoClasses = !generatesSources && !anchor.workspaceScoped();
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

            // An `always` script judges state the key cannot see (build output it reclaims), so a
            // hit on the same sources says nothing about what it would do now.
            boolean useCache = !task.always()
                    && !SessionContext.current().config().forceOr(false)
                    && !SessionContext.current().config().rebuildOr(false);
            Optional<ActionCache.ActionRecord> hit = useCache ? actionCache.lookup(key) : Optional.empty();
            if (hit.isPresent()) {
                // A record with no outputs is a VERDICT, not a miss: the script ran on these exact
                // inputs and produced nothing, which is the whole result of a check. Skipping it is
                // the point — before this, "writes nothing" meant "runs on every build forever".
                if (hit.get().outputs().isEmpty()) {
                    deleteContents(outDir);
                    Files.createDirectories(outDir);
                    label.accept("build-logic:" + simple + ": cache hit");
                    continue;
                }
                deleteContents(outDir);
                Files.createDirectories(outDir);
                if (actionCache.restore(hit.get(), outDir)) {
                    label.accept("build-logic:" + simple + ": cache hit");
                    if (mergesIntoClasses) mergeIntoClasses(outDir, classesDir);
                    continue;
                }
            }

            label.accept("build-logic:" + simple + ": " + anchor.name().toLowerCase(Locale.ROOT));
            deleteContents(outDir);
            Files.createDirectories(outDir);
            try {
                String captured = task.run()
                        .run(
                                projectDir.toAbsolutePath().normalize(),
                                outDir.toAbsolutePath().normalize());
                // Whatever the script printed goes to the same sink native-image and the compilers
                // use: buffered for the Ctrl-O peek ring, above the live region, printed under
                // `-v`. Only the success path emits — a failure throws above with its output
                // already attached to the message.
                emitLines(task.source(), captured, output);
            } catch (Exception e) {
                if (e instanceof InterruptedException ie) throw ie;
                if (e instanceof IOException ioe) throw ioe;
                throw taskFailure(simple, e);
            }
            // Only a success is recorded: the throw above leaves this line unreached, so a failing
            // script is re-run next build rather than replaying its own red.
            Map<String, String> inputs = Map.of(TaskNames.BUILD_LOGIC, key);
            if (task.always()) {
                // Nothing to replay: the next build asks the question again.
            } else if (isEmptyDir(outDir)) {
                actionCache.storeVerdict(taskId, key, inputs);
            } else {
                actionCache.store(taskId, key, inputs, outDir);
            }
            if (mergesIntoClasses) mergeIntoClasses(outDir, classesDir);
        }
        return true;
    }

    /** Run {@link BuildLogicAnchor#AFTER_RESOURCES} only. */
    public static boolean run(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            @Nullable Path classesDir,
            Consumer<String> label)
            throws IOException, InterruptedException {
        return run(projectDir, layout, actionCache, classesDir, BuildLogicAnchor.AFTER_RESOURCES, label);
    }

    @FunctionalInterface
    private interface ScriptRun {
        /** @return the script's captured stdout/stderr, for the caller's output sink */
        String run(Path projectDir, Path outDir) throws Exception;
    }

    /** @param source the script's file name, for attributing its output */
    private record RegisteredTask(String name, String kind, ScriptRun run, boolean always, String source) {}

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
                    return kind == BuildLogicScripts.ScriptKind.KTS
                            ? BuildLogicKtsHost.evaluate(scriptFile, projectDir, outDir)
                            : BuildLogicGroovyHost.evaluate(scriptFile, projectDir, outDir);
                } catch (Exception e) {
                    Throwable root = e;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    if (root instanceof InterruptedException ie) throw ie;
                    if (root instanceof IOException ioe) throw ioe;
                    throw scriptFailure(scriptFile, root);
                }
            };
            String kindLabel = kind == BuildLogicScripts.ScriptKind.KTS ? "script-kts" : "script";
            byAnchor.computeIfAbsent(s.anchor(), k -> new ArrayList<>())
                    .add(new RegisteredTask(
                            s.name(),
                            kindLabel,
                            task,
                            s.always(),
                            s.file().getFileName().toString()));
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

    /**
     * What a workspace-root script can read, as cache-key tokens: <strong>every file in the
     * checkout</strong> except build output and VCS metadata.
     *
     * <p>Not "every member's source roots", which was the first shape and was wrong. A root check
     * reads the workspace, and a workspace is more than the union of its modules' {@code src}
     * trees: the gate that motivated this reads {@code size-baseline.txt}, {@code
     * code-as-art.md}, {@code settings.gradle.kts} and every module's {@code build.gradle.kts},
     * none of which belongs to any member's fingerprint. A key that missed them would go on
     * replaying a green verdict after the very file the check reads had changed.
     *
     * <p>So the rule is the same one {@link #projectInputTokens} follows, applied at the scope that
     * actually matches the reach: a script declares no inputs, so the key covers everything it
     * could consume. Hashes come from {@link FileHashMemo}, so the steady-state cost is one stat
     * per file rather than a re-read.
     */
    private static List<String> workspaceInputTokens(Path rootDir) throws IOException {
        Path root = rootDir.toAbsolutePath().normalize();
        List<String> tokens = new ArrayList<>();
        PathUtil.forEachRegularFile(root, WalkSkip::workspaceKey, (file, attrs) -> {
            Path abs = file.toAbsolutePath().normalize();
            tokens.add("ws:" + root.relativize(abs) + ":" + FileHashMemo.contentHash(abs, attrs));
        });
        Collections.sort(tokens);
        return tokens;
    }

    /**
     * Path+content tokens for one tree, relative to {@code base}; missing trees contribute none.
     *
     * <p>Through {@link PathUtil#forEachRegularFile} and {@link FileHashMemo}, not a raw walk and a
     * fresh digest: this runs over every source file of every module on a workspace build, and
     * re-reading bytes whose stat identity has not moved is the whole cost of deciding that nothing
     * changed.
     */
    private static void hashTree(Path base, Path dir, String prefix, List<String> out) throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return;
        Path from = base.toAbsolutePath().normalize();
        PathUtil.forEachRegularFile(root, (file, attrs) -> {
            Path abs = file.toAbsolutePath().normalize();
            out.add(prefix + ":" + from.relativize(abs) + ":" + FileHashMemo.contentHash(abs, attrs));
        });
    }

    /** True when {@code dir} holds no regular file at any depth. */
    private static boolean isEmptyDir(Path dir) throws IOException {
        return !PathUtil.anyRegularFile(dir, d -> false, p -> true);
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
        // Guard G45: regular-file-ness comes from the walk's own attributes.
        List<Path> hits = new ArrayList<>();
        PathUtil.forEachRegularFile(root, (f, attrs) -> {
            if (f.toString().endsWith(suffix)) hits.add(f);
        });
        return hits.stream().sorted().toList();
    }

    private static void mergeIntoClasses(Path generated, @Nullable Path classesDir) throws IOException {
        if (classesDir == null) return;
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
