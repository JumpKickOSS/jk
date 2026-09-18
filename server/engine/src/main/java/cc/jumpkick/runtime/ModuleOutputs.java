// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.FreshnessStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                                    moduleDir, compact, SessionContext.current().testSelection()),
                            BuildLayout.of(moduleDir, build),
                            null)
                    .isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@link #compileOutputsOnDisk(ActionCache, String, Path)} against the key the tree's own
     * Java freshness stamp names — the compile that produced it. A tree with no stamp, or a stamp
     * that names no key (a Kotlin- or Groovy-only module), has nothing to be held against.
     */
    public static boolean compileOutputsOnDisk(ActionCache actionCache, Path classesDir) {
        return compileOutputsOnDisk(
                actionCache,
                FreshnessStamp.stampedKey(classesDir, BuildStamps.JAVA).orElse(null),
                classesDir);
    }

    /**
     * True when every output the compile record for {@code key} owns is present under {@code
     * dir}. Presence only — one stat per owned output, no digest is re-read — because the question
     * is whether the tree is whole, not whether it is unmodified: a tree that is a strict subset of
     * what the record produced is the case a hitting key cannot see on its own. A null key or a
     * record that is gone (pruned) leaves nothing to compare and answers true; the stamp's or the
     * key's own evidence then stands. Extra files the record does not own are the restore's prune,
     * not this probe's concern.
     *
     * <p>A whole tree is remembered for the rest of the request. Five arms ask this of the same
     * (key, tree) pair in one build — the restore gate, the package-key reconstruction, the
     * run-tests fingerprint, the preflight memo and compile-main's stamp arm — and nothing in a
     * build removes an output a hitting record owns, so the first yes holds until the request
     * ends. A no is never remembered: the module it schedules restores the tree before its later
     * arms ask again, and they must see the restored tree.
     */
    public static boolean compileOutputsOnDisk(ActionCache actionCache, @Nullable String key, Path dir) {
        if (key == null || key.isBlank()) return true;
        Probe probe = new Probe(key, dir.toAbsolutePath().normalize());
        Set<Probe> whole = RequestScope.current().get(WHOLE_TREES, k -> ConcurrentHashMap.newKeySet());
        if (whole.contains(probe)) return true;
        boolean result = outputsPresent(actionCache, key, probe.dir());
        if (result) whole.add(probe);
        return result;
    }

    /** The request-scope key of the set of (record key, tree) pairs already found whole. */
    private static final Object WHOLE_TREES = new Object();

    /** One wholeness question: a compile record's key and the tree held against it. */
    private record Probe(String key, Path dir) {}

    private static boolean outputsPresent(ActionCache actionCache, String key, Path dir) {
        Optional<ActionCache.ActionRecord> record;
        try {
            record = actionCache.lookup(key);
        } catch (IOException e) {
            return true;
        }
        if (record.isEmpty()) return true;
        for (String rel : record.get().outputs().keySet()) {
            if (!Files.exists(dir.resolve(rel))) return false;
        }
        return true;
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
