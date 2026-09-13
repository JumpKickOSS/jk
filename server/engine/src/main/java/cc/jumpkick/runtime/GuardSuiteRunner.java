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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

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
            boolean workspace,
            /** Source roots Text reads, or empty for the module's/workspace's own; set for a fixture run. */
            List<Path> textRoots,
            /** A tree fixture's case: the directory Text paths resolve against, run as the checkout root; null otherwise. */
            @Nullable Path textRoot) {

        Inputs(
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
                boolean workspace) {
            this(
                    root,
                    module,
                    moduleDir,
                    layout,
                    javaHome,
                    runtimeClasspath,
                    cacheRoot,
                    factsIndexes,
                    testFactsIndexes,
                    classDirs,
                    workspace,
                    List.of(),
                    null);
        }
    }

    /** Forks the suite; returns the problems that stop the run from meaning anything (empty = ran). */
    static List<String> run(Inputs in, List<Path> workspaceModules) throws IOException, InterruptedException {
        return run(in, workspaceModules, GuardSuites.report(BuildLayout.moduleTargetDir(in.root(), in.moduleDir())));
    }

    /** As above, with the report (and its run files) beside {@code report} — fixtures run the suite off to the side. */
    static List<String> run(Inputs in, List<Path> workspaceModules, Path report)
            throws IOException, InterruptedException {
        Path guardDir = Objects.requireNonNull(report.toAbsolutePath().getParent(), "report has a parent");
        Files.createDirectories(guardDir);
        // The fork appends to a side file; the report itself appears whole or not at all, so a run
        // cut short (a sibling lane failed first) never leaves a partial report for freeze or explain.
        Path part = report.resolveSibling(report.getFileName() + ".part");
        Files.deleteIfExists(part);
        Path model = guardDir.resolve("model.json");
        GuardModelSnapshot.write(in.root(), workspaceModules, model);
        // Source roots, never module directories: the text view walks what it is given, and a module
        // directory would take the build output along.
        List<Path> sources = new ArrayList<>(in.textRoots());
        if (sources.isEmpty()) {
            if (in.workspace()) {
                sources.add(sourceRoot(in.root()));
                for (Path m : workspaceModules) sources.add(sourceRoot(m));
            } else {
                sources.add(sourceRoot(in.moduleDir()));
            }
        }
        GuardConfig config = new GuardConfig(
                part,
                in.root(),
                in.module(),
                in.factsIndexes(),
                in.testFactsIndexes(),
                model,
                sources,
                in.classDirs(),
                List.of(),
                List.of(),
                null,
                // a file fixture has no tree shape, so globs match by name; a tree case has the real one
                !in.textRoots().isEmpty() && in.textRoot() == null,
                in.textRoot());
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
        // The forked JVM's own words, kept for the one case they explain something: a suite that
        // ran more guards than it reported.
        List<String> said = new ArrayList<>();
        TestProgressListener listener = new TestProgressListener() {
            @Override
            public void onUserOutput(int workerId, String line) {
                if (said.size() < 400) said.add(line);
            }

            @Override
            public void onFailure(
                    String id,
                    String label,
                    String exClass,
                    String message,
                    String stack,
                    String engine,
                    String className,
                    String method,
                    int workerId) {
                said.add("FAILED " + label + ": " + exClass + ": " + message);
                for (String l : stack.split("\n")) {
                    if (said.size() >= 400) break;
                    said.add("  " + l);
                }
            }
        };
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
                        listener,
                        null);
        // What the fork said, next to the report it left: the place to look when a guard is missing from it.
        StringBuilder log = new StringBuilder("total=" + summary.total() + " succeeded=" + summary.succeeded()
                + " failed=" + summary.failed() + " skipped=" + summary.skipped() + "\n");
        for (String l : said) log.append(l).append('\n');
        Files.writeString(report.resolveSibling("junit.log"), log.toString());
        if (Files.isRegularFile(part))
            Files.move(part, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        else Files.deleteIfExists(report);
        List<String> problems = new ArrayList<>();
        if (summary.failed() > 0) {
            problems.add("the guard suite's JUnit run failed " + summary.failed() + " test(s) outside any @Guard: "
                    + summary.failures());
        }
        if (summary.total() == 0)
            problems.add("the guard suite ran no @Guard method: is the class annotated @GuardSuite?");
        long reported = Files.isRegularFile(report) ? Files.readAllLines(report).size() : 0;
        if (reported < summary.total()) {
            StringBuilder sb = new StringBuilder("the suite ran " + summary.total() + " guard(s) but reported "
                    + reported + "; the forked JVM said:");
            int from = Math.max(0, said.size() - 40);
            for (String l : said.subList(from, said.size())) sb.append("\n    ").append(l);
            problems.add(sb.toString());
        }
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
