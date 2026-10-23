// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.FreshnessStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Which of a module's PACKAGE outputs are missing while its inputs are unchanged: the main jar,
 * a sourced module's classes tree, a resources-only module's copied tree, the assembly jar, the
 * native binary — {@code jk clean}, hand-deleted artifacts, partial wipes. A module that answers
 * yes is scheduled, and its own plan brings the outputs back: every step restores by the action
 * key it computes from its current inputs, so the tree and the jar that return are the ones this
 * build would produce.
 */
@NullMarked
public final class ModuleOutputs {

    private ModuleOutputs() {}

    /**
     * True when this module's required PACKAGE outputs are absent: main jar missing, a sourced
     * module with an empty classes tree, or a resources-only module whose copied tree or jar is
     * gone. Without an action cache the classes tree is judged by presence alone.
     */
    public static boolean packageOutputsMissing(Path workspaceRoot, Path moduleDir, JkBuild build) {
        return packageOutputsMissing(workspaceRoot, moduleDir, build, null);
    }

    /**
     * As above, and with {@code actionCache} a non-empty classes tree is also held against the
     * compile record its stamp names: a tree that lacks an output that record owns is a missing
     * output too, or a build after a partial wipe skips the module forever — the key hits, the
     * tree is non-empty, and the jar packaged from it lacks the same classes.
     */
    public static boolean packageOutputsMissing(
            Path workspaceRoot, Path moduleDir, JkBuild build, @Nullable ActionCache actionCache) {
        if (NodeShape.kind(build, moduleDir) == NodeShape.Kind.MODULE)
            return nodeOutputsMissing(moduleDir, build, actionCache);
        BuildLayout layout = BuildLayout.of(workspaceRoot, moduleDir, build);
        if (hasMainSources(moduleDir, build)) {
            if (!Files.isRegularFile(layout.mainJar())) return true;
            if (!classesDirHasContent(layout.classesDir())) return true;
            if (actionCache != null && !compileOutputsOnDisk(actionCache, layout.classesDir())) return true;
        } else if (resourcesOnlyOutputsMissing(moduleDir, build, layout)) {
            return true;
        }
        if (build.assembly() && !Files.isRegularFile(layout.assemblyJar())) return true;
        if (build.nativeMode() == JkBuild.NativeMode.ALWAYS && !nativePresent(layout, build)) return true;
        return false;
    }

    /**
     * A node module's outputs: its {@code node_modules}, its resource jar when it packages one, and
     * the build's output directory holding every file the last {@code node-build} record names (with
     * {@code actionCache}; without one, present at all). A skipped node build has none to miss.
     */
    static boolean nodeOutputsMissing(Path moduleDir, JkBuild build, @Nullable ActionCache actionCache) {
        if (build.node().skip() || SessionContext.current().skipNode()) return false;
        Path nodeDir = NodeShape.nodeDir(build, moduleDir);
        if (nodeDir == null) return false;
        if (!Files.isDirectory(nodeDir.resolve("node_modules"))) return true;
        NodeProject node = NodeProject.infer(nodeDir, build.node());
        if (node.build() == null) return false;
        Path out = nodeDir.resolve(node.out());
        if (!Files.isDirectory(out)) return true;
        try {
            if (NodePackaging.classpathRoot(build, moduleDir) != null
                    && !Files.isRegularFile(BuildLayout.of(moduleDir, build).mainJar())) {
                return true;
            }
        } catch (IOException e) {
            return true;
        }
        if (actionCache == null) return false;
        try {
            Optional<ActionCache.ActionRecord> last =
                    actionCache.lastFor(ActionKey.qualifiedTaskId(TaskNames.NODE_BUILD, nodeDir));
            if (last.isEmpty()) return false;
            for (String rel : last.get().outputs().keySet()) {
                if (!Files.isRegularFile(out.resolve(rel))) return true;
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * True when this module's test view is absent while its inputs are unchanged: an empty test
     * classes tree for a module with test sources, or an empty fixtures tree for a module whose
     * declared fixtures root holds sources. The test view is an output of a tests-enabled build as
     * much as the jar is — the module's own suite runs from it, and a sibling's {@code fixtures =
     * true} or {@code kind = "tests"} edge compiles against it — so a build whose records all hit
     * still schedules the module to restore it, or the sibling that reads it is admitted against a
     * tree nothing produces. A {@code --skip-tests} build produces no test view and asks this
     * nothing.
     *
     * @param hasTests whether the module has test sources under the session's suite selection;
     *     asked only when the test classes tree is empty, because answering may walk the sources
     */
    public static boolean testViewMissing(BuildLayout layout, JkBuild build, Path moduleDir, BooleanSupplier hasTests) {
        if (!classesDirHasContent(layout.testClassesDir()) && hasTests.getAsBoolean()) return true;
        return PlannerFixtures.declared(build)
                && !PlannerFixtures.forecastSources(build, moduleDir).isEmpty()
                && !classesDirHasContent(layout.testFixturesClassesDir());
    }

    /**
     * Whether the module has test sources of its own under the session's suite selection; an
     * unreadable tree reads as none, and a plugin's generated test sources are not counted.
     */
    public static boolean hasSelectedTestSources(JkBuild build, Path moduleDir) {
        boolean compact = CompileSupport.isSimpleLayout(build.project(), moduleDir);
        try {
            return !PlannerTest.TestSources.collect(
                            build,
                            moduleDir,
                            compact,
                            TestSupport.selectedSuites(
                                    moduleDir,
                                    compact,
                                    build.build(),
                                    SessionContext.current().testSelection()),
                            BuildLayout.of(moduleDir, build),
                            null)
                    .isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /** The stamps a merged classes tree carries, one per compile whose outputs it holds. */
    private static final List<String> TREE_STAMPS = List.of(BuildStamps.JAVA, BuildStamps.KOTLIN, BuildStamps.GROOVY);

    /**
     * {@link #compileOutputsOnDisk(ActionCache, List, Path)} against every key the tree's own
     * freshness stamps name — the Java, Kotlin and Groovy compiles whose outputs are merged into
     * it. A stamp that is absent, or names no key, holds the tree against nothing.
     */
    public static boolean compileOutputsOnDisk(ActionCache actionCache, Path classesDir) {
        List<@Nullable String> keys = new ArrayList<>();
        for (String stamp : TREE_STAMPS)
            keys.add(FreshnessStamp.stampedKey(classesDir, stamp).orElse(null));
        return compileOutputsOnDisk(actionCache, keys, classesDir);
    }

    /** As {@link #compileOutputsOnDisk(ActionCache, String, Path)}, against the record the stamp named {@code stamp} points at. */
    public static boolean compileOutputsOnDisk(ActionCache actionCache, Path classesDir, String stamp) {
        return compileOutputsOnDisk(
                actionCache, FreshnessStamp.stampedKey(classesDir, stamp).orElse(null), classesDir);
    }

    /**
     * True when every output the compile record for {@code key} owns is in {@code dir} with the
     * digest the record gives it, or the one the module's last successful build left there ({@link
     * ModuleInputProvenance#treeDigests}: a step that rewrites the classes in place). Digests come
     * through {@link FileHashMemo}, so an untouched output costs one stat. A null key holds the tree
     * against nothing; a key whose record is gone or will not read answers false, since nothing then
     * vouches for the tree and the step should restore or run.
     */
    public static boolean compileOutputsOnDisk(ActionCache actionCache, @Nullable String key, Path dir) {
        return compileOutputsOnDisk(actionCache, Collections.singletonList(key), dir, false);
    }

    /**
     * As {@link #compileOutputsOnDisk(ActionCache, String, Path)} for a tree every compile in {@code
     * keys} writes into, which also holds every class file in it to one of their records: a class
     * no compile produced (an IDE builder's, a hand-copied one) would otherwise ride into the jar.
     */
    public static boolean compileOutputsOnDisk(ActionCache actionCache, List<@Nullable String> keys, Path dir) {
        return compileOutputsOnDisk(actionCache, keys, dir, true);
    }

    private static boolean compileOutputsOnDisk(
            ActionCache actionCache, List<@Nullable String> keys, Path dir, boolean wholeTree) {
        List<String> named = new ArrayList<>();
        for (String k : keys) if (k != null && !k.isBlank()) named.add(k);
        if (named.isEmpty()) return true;
        Probe probe = new Probe(List.copyOf(named), dir.toAbsolutePath().normalize(), wholeTree);
        Set<Probe> whole = RequestScope.current().get(WHOLE_TREES, k -> ConcurrentHashMap.newKeySet());
        if (whole.contains(probe)) return true;
        boolean result = outputsMatch(actionCache, probe.keys(), probe.dir(), wholeTree);
        if (result) whole.add(probe);
        return result;
    }

    /** The request-scope key of the set of (record keys, tree) pairs already found whole. */
    private static final Object WHOLE_TREES = new Object();

    /** One wholeness question: the compile records' keys and the tree held against them. */
    private record Probe(List<String> keys, Path dir, boolean wholeTree) {}

    private static boolean outputsMatch(ActionCache actionCache, List<String> keys, Path dir, boolean wholeTree) {
        Set<String> owned = new HashSet<>();
        try {
            for (String key : keys) {
                Optional<ActionCache.ActionRecord> record = actionCache.lookup(key);
                if (record.isEmpty()) return false;
                Map<String, String> built = ModuleInputProvenance.treeDigests(dir, key);
                for (Map.Entry<String, String> out : record.get().outputs().entrySet()) {
                    Path file = dir.resolve(out.getKey());
                    if (!Files.isRegularFile(file)) return false;
                    String now = FileHashMemo.contentHash(file.toAbsolutePath().normalize());
                    if (!now.equals(out.getValue()) && !now.equals(built.get(out.getKey()))) return false;
                    owned.add(out.getKey());
                }
            }
            if (!wholeTree) return true;
            boolean[] foreign = {false};
            PathUtil.forEachRegularFile(dir, (file, attrs) -> {
                String rel = dir.relativize(file).toString().replace('\\', '/');
                if (rel.endsWith(".class") && !owned.contains(rel)) foreign[0] = true;
            });
            return !foreign[0];
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private static boolean nativePresent(BuildLayout layout, JkBuild build) {
        // nativeBinary() already applies [native].name and the Windows .exe suffix.
        if (Files.isRegularFile(layout.nativeBinary())) return true;
        String named = build.nativeConfigOpt()
                .map(JkBuild.NativeConfig::name)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);
        Path libBase = named != null
                ? layout.moduleTargetDir().resolve(named.startsWith("lib") ? named : "lib" + named)
                : layout.nativeLibrary();
        return Files.isRegularFile(Path.of(libBase + ".so"))
                || Files.isRegularFile(Path.of(libBase + ".dylib"))
                || Files.isRegularFile(Path.of(libBase + ".dll"));
    }

    /**
     * A module with resources and no sources: the tree {@code copy-resources} fills is on every
     * dependent's compile classpath and its jar is what their package steps read, so either gone
     * while the resources stand is a missing output. A module with neither, or a workspace root,
     * plans no package step and asks for nothing. Any file in the tree counts — a resource is not
     * a class file.
     */
    private static boolean resourcesOnlyOutputsMissing(Path moduleDir, JkBuild build, BuildLayout layout) {
        if (build.isWorkspaceRoot()) return false;
        boolean compact = CompileSupport.isSimpleLayout(build.project(), moduleDir);
        if (PackagingKeys.packageResourceRoots(moduleDir, compact).isEmpty()) return false;
        try {
            return !Files.isRegularFile(layout.mainJar()) || !TaskForecaster.classesDirHasContent(layout.classesDir());
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean hasMainSources(Path moduleDir, JkBuild build) {
        try {
            boolean compact = CompileSupport.isSimpleLayout(build.project(), moduleDir);
            Path javaRoot = compact ? moduleDir.resolve("src") : moduleDir.resolve("src/main/java");
            return !CompileSupport.collectJavaSources(javaRoot).isEmpty()
                    || !PlannerCompile.mainKotlinSources(build, moduleDir, compact)
                            .isEmpty()
                    || !PlannerCompile.mainGroovySources(build, moduleDir, compact)
                            .isEmpty()
                    || !CompileSupport.collectScalaSources(moduleDir, compact).isEmpty();
        } catch (Exception e) {
            return true; // fail safe: treat as sourced so missing classes counts
        }
    }

    static boolean classesDirHasContent(Path classesDir) {
        if (classesDir == null || !Files.isDirectory(classesDir)) return false;
        // Unbounded walk (findFirst short-circuits at the first class file): a depth cap of 3
        // missed every package deeper than three segments — cc/jumpkick/... classes sit at
        // depth 4+, so every self-host module would read as "outputs missing" and the restore
        // path would re-run the whole workspace on a fully-cached build.
        try (var walk = Files.find(
                classesDir,
                Integer.MAX_VALUE,
                (p, attrs) -> attrs.isRegularFile()
                        && p.getFileName() != null
                        && p.getFileName().toString().endsWith(".class"))) {
            return walk.findFirst().isPresent();
        } catch (IOException e) {
            return false;
        }
    }
}
