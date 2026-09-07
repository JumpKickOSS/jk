// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.guard.api.runtime.GuardConfig;
import cc.jumpkick.guard.eval.GuardModelSnapshot;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.TestProgressListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs a module's compiled {@code src/guard} suite in the forked JUnit launcher and leaves its
 * report where the {@code test} kind's evaluator reads it. The engine prepares every input — the
 * facts indexes, a model snapshot, the source roots — so the forked JVM parses and resolves nothing;
 * a violation never fails the JUnit run, and the lane reconciles the report like TOML output.
 */
final class GuardSuiteRunner {

    private GuardSuiteRunner() {}

    /** What one run needs beyond the lane's own context. */
    record Inputs(
            Path root,
            String module,
            Path moduleDir,
            BuildLayout layout,
            Path javaHome,
            List<Path> runtimeClasspath,
            Path cacheRoot,
            List<Path> factsIndexes,
            List<Path> testFactsIndexes,
            List<Path> classDirs,
            boolean workspace) {}

    /** Forks the suite; returns the problems that stop the run from meaning anything (empty = ran). */
    static List<String> run(Inputs in, List<Path> workspaceModules) throws IOException, InterruptedException {
        Path guardDir = BuildLayout.moduleTargetDir(in.root(), in.moduleDir()).resolve("guard");
        Files.createDirectories(guardDir);
        Path report = GuardSuites.report(BuildLayout.moduleTargetDir(in.root(), in.moduleDir()));
        Files.deleteIfExists(report);
        Path model = guardDir.resolve("model.json");
        GuardModelSnapshot.write(in.root(), workspaceModules, model);
        // Source roots, never module directories: the text view walks what it is given, and a module
        // directory would take the build output along.
        List<Path> sources = new ArrayList<>();
        if (in.workspace()) {
            sources.add(sourceRoot(in.root()));
            for (Path m : workspaceModules) sources.add(sourceRoot(m));
        } else {
            sources.add(sourceRoot(in.moduleDir()));
        }
        GuardConfig config = new GuardConfig(
                report,
                in.root(),
                in.module(),
                in.factsIndexes(),
                in.testFactsIndexes(),
                model,
                sources,
                in.classDirs(),
                List.of(),
                List.of(),
                null);
        Path props = guardDir.resolve("run.properties");
        Files.writeString(props, config.toProperties());
        if (!hasJUnitEngine(in.runtimeClasspath())) {
            return List.of(
                    "the guard suite needs the JUnit Jupiter engine on the test runtime classpath: add junit-jupiter to"
                            + " [test-dependencies]");
        }
        List<Path> cp = new ArrayList<>();
        cp.add(in.layout().guardClassesDir());
        for (Path p : in.runtimeClasspath()) if (!cp.contains(p)) cp.add(p);
        TestSummary summary = new JUnitLauncher()
                .withModuleLabel("guard " + (in.module().isEmpty() ? "root" : in.module()))
                .run(
                        in.javaHome(),
                        in.layout().guardClassesDir(),
                        cp,
                        in.cacheRoot(),
                        1,
                        Map.of(
                                GuardConfig.PROPERTY,
                                props.toAbsolutePath().toString(),
                                "junit.jupiter.extensions.autodetection.enabled",
                                "true"),
                        new TestProgressListener() {},
                        null);
        List<String> problems = new ArrayList<>();
        if (summary.failed() > 0) {
            problems.add("the guard suite's JUnit run failed " + summary.failed() + " test(s) outside any @Guard: "
                    + summary.failures());
        }
        if (summary.total() == 0)
            problems.add("the guard suite ran no @Guard method: is the class annotated @GuardSuite?");
        return problems;
    }

    /** {@code <dir>/src} in the standard layout; the module itself in the compact one. */
    private static Path sourceRoot(Path moduleDir) {
        Path src = moduleDir.resolve("src");
        return Files.isDirectory(src) ? src : moduleDir;
    }

    private static boolean hasJUnitEngine(List<Path> classpath) {
        Set<String> names = new TreeSet<>();
        for (Path p : classpath)
            names.add(p.getFileName() == null ? "" : p.getFileName().toString());
        for (String n : names) if (n.startsWith("junit-jupiter-engine") || n.startsWith("junit-jupiter-")) return true;
        return false;
    }
}
