// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.test.TestProgressListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Test-step building blocks shared by the build plan and the {@code test} command. Coupled only
 * to {@link TaskContext} (the view-agnostic progress callback), so it lives in {@code:runtime}
 * and embedders can drive it without the CLI/TUI.
 */
public final class TestSupport {

    private TestSupport() {}

    private static final Pattern TEST_ANNOTATION_REGEX =
            Pattern.compile("@(?:Test|ParameterizedTest|TestFactory|TestTemplate|RepeatedTest)\\b");

    /**
     * Best-effort count of JUnit test methods under {@code testSrcDir} — scans {@code.java}/{@code
     * .kt} sources for {@code @Test}-family annotations. Feeds the build's {@code estimatedTestCount}
     * (progress-bar weighting); a zero estimate falls back to a flat bar. Never throws.
     */
    public static int estimateTestCount(Path testSrcDir) {
        if (!Files.isDirectory(testSrcDir)) return 0;
        int count = 0;
        try (Stream<Path> walk = Files.walk(testSrcDir)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".groovy");
            })::iterator) {
                try {
                    String content = Files.readString(file);
                    count += (int)
                            TEST_ANNOTATION_REGEX.matcher(content).results().count();
                } catch (IOException ignored) {
                    // best-effort: skip unreadable files, keep counting
                }
            }
        } catch (IOException ignored) {
            // best-effort: zero estimate falls back to a flat (empty) bar
        }
        return count;
    }

    /**
     * Count test methods across every discovered suitenot default-suite only.
     * Dedupes when java/kotlin roots share a directory (SIMPLE layout).
     */
    public static int estimateAllSuiteTestCount(Path moduleDir, boolean compact) {
        int total = 0;
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        for (String suite : cc.jumpkick.layout.TestSuites.discover(moduleDir, compact)) {
            roots.addAll(cc.jumpkick.layout.TestSuites.javaRoots(moduleDir, compact, suite));
            roots.addAll(cc.jumpkick.layout.TestSuites.kotlinRoots(moduleDir, compact, suite));
            roots.addAll(cc.jumpkick.layout.TestSuites.groovyRoots(moduleDir, compact, suite));
        }
        for (Path r : roots) total += estimateTestCount(r);
        return total;
    }

    /**
     * Count test methods for the suites a SELECTION will actually runsizing the
     * bar/ETA with every discovered suite made plain `jk test` under-fill and snap to 100 when
     * an integration suite existed. Unresolvable selections fall back to all discovered suites.
     */
    public static int estimateSelectedSuiteTestCount(
            Path moduleDir, boolean compact, cc.jumpkick.config.TestSelection selection) {
        java.util.List<String> discovered = cc.jumpkick.layout.TestSuites.discover(moduleDir, compact);
        java.util.List<String> suites = discovered;
        if (selection != null) {
            var resolved = selection.resolve(discovered);
            if (resolved.ok()) suites = resolved.suites();
        }
        int total = 0;
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        for (String suite : suites) {
            roots.addAll(cc.jumpkick.layout.TestSuites.javaRoots(moduleDir, compact, suite));
            roots.addAll(cc.jumpkick.layout.TestSuites.kotlinRoots(moduleDir, compact, suite));
            roots.addAll(cc.jumpkick.layout.TestSuites.groovyRoots(moduleDir, compact, suite));
        }
        for (Path r : roots) total += estimateTestCount(r);
        return total;
    }

    /**
     * Best-effort count of test <em>classes</em> (source files with ≥1 test annotation) under
     * discovered suites — hierarchical effort tier between method and step.
     */
    public static int estimateAllSuiteTestClassCount(Path moduleDir, boolean compact) {
        int total = 0;
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        for (String suite : cc.jumpkick.layout.TestSuites.discover(moduleDir, compact)) {
            roots.addAll(cc.jumpkick.layout.TestSuites.javaRoots(moduleDir, compact, suite));
            roots.addAll(cc.jumpkick.layout.TestSuites.kotlinRoots(moduleDir, compact, suite));
            roots.addAll(cc.jumpkick.layout.TestSuites.groovyRoots(moduleDir, compact, suite));
        }
        for (Path r : roots) total += estimateTestClassCount(r);
        return total;
    }

    /** Source files under {@code testSrcDir} that contain at least one JUnit test annotation. */
    public static int estimateTestClassCount(Path testSrcDir) {
        if (!Files.isDirectory(testSrcDir)) return 0;
        int count = 0;
        try (Stream<Path> walk = Files.walk(testSrcDir)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".groovy");
            })::iterator) {
                try {
                    String content = Files.readString(file);
                    if (TEST_ANNOTATION_REGEX.matcher(content).find()) count++;
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return count;
    }

    /** Collect all test sources for every discovered suite (deduped paths). */
    public static List<Path> collectAllSuiteTestSources(Path moduleDir, boolean compact) throws IOException {
        java.util.LinkedHashSet<Path> out = new java.util.LinkedHashSet<>();
        List<String> suites = cc.jumpkick.layout.TestSuites.discover(moduleDir, compact);
        if (suites.isEmpty()) {
            // Fall back to default suite dirs even if empty of sources
            suites = List.of(cc.jumpkick.layout.TestSuites.DEFAULT);
        }
        out.addAll(cc.jumpkick.layout.TestSuites.collectJavaSources(moduleDir, compact, suites));
        out.addAll(cc.jumpkick.layout.TestSuites.collectKotlinSources(moduleDir, compact, suites));
        out.addAll(cc.jumpkick.layout.TestSuites.collectGroovySources(moduleDir, compact, suites));
        return new java.util.ArrayList<>(out);
    }

    /**
     * Plain-text failure report for the CLI to paint. Layout (no ANSI — the client styles it):
     *
     * <pre>
     * Test Failure
     * 1 test failed:
     *
     *   FAILED  group:artifact :: method()
     *     class: fqcn
     *     java.lang.AssertionError
     *
     * Expecting actual:
     *   21670L
     * …
     * 	at …
     * </pre>
     *
     * <p>Returns empty when nothing failed. The leading {@code Test Failure} title is a fixed sentinel
     * the CLI rewrites into a red pill + "Failure".
     */
    public static List<String> renderFailures(TestSummary result) {
        List<String> out = new ArrayList<>();
        List<TestSummary.Failure> failures = result.failures();
        if (failures.isEmpty()) return out;
        // No leading blank — the CLI leaves a single blank under the prompt / live region.
        out.add("Test Failure");
        out.add(failures.size() + " test" + (failures.size() == 1 ? "" : "s") + " failed:");
        for (TestSummary.Failure f : failures) {
            out.add("");
            // module:: display [wN] so parallel monorepo flakes are locatable.
            out.add("  FAILED  " + f.headline());
            if (f.className() != null
                    && !f.className().isBlank()
                    && !f.headline().contains(f.className())) {
                out.add("    class: " + f.className());
            }
            if (!f.exceptionClass().isEmpty()) {
                out.add("    " + f.exceptionClass());
            }
            // Assertion / failure body (prefer discrete message; else extract from stack).
            List<String> body = failureBodyLines(f);
            if (!body.isEmpty()) {
                out.add("");
                out.addAll(body);
            }
            // Stack frames only (skip exception header already printed above).
            List<String> frames = failureStackFrames(f);
            if (!frames.isEmpty()) {
                out.add("");
                out.addAll(frames);
            }
        }
        // No trailing blank — the settle wedge ("✘ Build …") follows immediately.
        return out;
    }

    /** Human-facing assertion / message lines (no stack frames, no exception FQCN prefix). */
    static List<String> failureBodyLines(TestSummary.Failure f) {
        String msg = f.message() == null ? "" : f.message().strip();
        if (!msg.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (String line : msg.split("\n", -1)) {
                lines.add(line); // keep indent; CLI paints
            }
            // Drop a trailing blank.
            while (!lines.isEmpty() && lines.getLast().isBlank()) lines.removeLast();
            return lines;
        }
        // Fall back: stack's message section between "Exception: " and first "at ".
        String details = f.details() == null ? "" : f.details();
        if (details.isBlank()) return List.of();
        List<String> lines = new ArrayList<>();
        boolean started = false;
        for (String line : details.split("\n", -1)) {
            String t = line.stripLeading();
            if (t.startsWith("at ") || t.startsWith("...")) break;
            if (!started) {
                // Skip "fqcn: message" first line's FQCN; keep rest of message if any.
                String ex = f.exceptionClass();
                if (ex != null && !ex.isEmpty() && t.startsWith(ex)) {
                    int colon = t.indexOf(':');
                    if (colon >= 0 && colon + 1 < t.length()) {
                        String rest = t.substring(colon + 1).stripLeading();
                        if (!rest.isEmpty()) lines.add(rest);
                    }
                    started = true;
                    continue;
                }
                started = true;
            }
            lines.add(line);
        }
        while (!lines.isEmpty() && lines.getLast().isBlank()) lines.removeLast();
        return lines;
    }

    /** {@code at …} / {@code ... N more} lines from the stack, preserving original indent. */
    static List<String> failureStackFrames(TestSummary.Failure f) {
        String details = f.details() == null ? "" : f.details();
        if (details.isBlank()) return List.of();
        List<String> frames = new ArrayList<>();
        for (String line : details.split("\n", -1)) {
            String t = line.stripLeading();
            if (t.startsWith("at ") || t.startsWith("...")) frames.add(line);
        }
        return frames;
    }

    /**
     * Adapt the JUnit runner's events onto a {@link TaskContext}.
     *
     * <p>The runTests step is built with its scope baked in from an upfront lexical scan, so the
     * plan's denominator is fixed before any step runs. We don't react to the runner's {@code
     * discovery_total} (that would reshape the bar after early steps moved). The numerator ticks
     * only for tests that were in the static plan ({@code wasStatic=true}); dynamic invocations run
     * and are counted in the pass/fail tally but never advance the bar — for parameterized-heavy
     * suites the bar saturates near 99% before execution ends and step-end auto-fill snaps it to
     * 100% on success.
     */
    public static TestProgressListener bridgeListener(TaskContext ctx, int workerCount, boolean verbose) {
        return bridgeListener(ctx, workerCount, verbose, "");
    }

    /**
     * As {@link #bridgeListener(TaskContext, int, boolean)} with a module coord for labels / failure
     * diagnostics.
     */
    public static TestProgressListener bridgeListener(
            TaskContext ctx, int workerCount, boolean verbose, String moduleLabel) {
        String module = moduleLabel == null ? "" : moduleLabel.trim();
        return new TestProgressListener() {
            @Override
            public void onTestStarted(String id, String display, boolean isTest, int workerId) {
                // Label at start so long-running tests/classes show as "current work" in the TUI
                // tree (finish-only labels lag one event behind). Prefer Class > method when the
                // unique id carries a class segment and display is the bare method name.
                // Skip engine/suite roots (no [class:…] segment) so we don't flash "JUnit Jupiter".
                if (!isTest
                        && cc.jumpkick.test.JUnitLauncher.classFromUniqueId(id).isEmpty()) {
                    return;
                }
                String detail = liveTestDetail(id, display, isTest);
                if (!detail.isBlank()) {
                    ctx.label(progressLabel(module, detail, workerId, workerCount));
                }
            }

            @Override
            public void onTestFinished(
                    String id,
                    String display,
                    String status,
                    boolean isTest,
                    boolean wasStatic,
                    long durationMs,
                    int workerId) {
                if (!isTest) return;
                if (wasStatic) ctx.progress(1);
                // Keep the label in sync on finish for fast suites (start+finish race); also
                // covers engines that omit start events for some nodes.
                ctx.label(progressLabel(module, liveTestDetail(id, display, true), workerId, workerCount));
            }

            @Override
            public void onTestSkipped(
                    String id, String display, String reason, boolean isTest, boolean wasStatic, int workerId) {
                if (!isTest) return;
                if (wasStatic) ctx.progress(1);
            }

            @Override
            public void onFailure(String id, String display, String exClass, String message, int workerId) {
                // Code "test-failure" (not "test") marks a per-test failure that is
                // already shown in full by the run-tests renderFailures block. The
                // diagnostic still flows to JSON consumers, but the human listeners
                // suppress it so the same failure isn't printed twice. Test *infra*
                // errors (interrupt/IO) keep code "test" and still surface in text mode.
                String label = progressLabel(module, liveTestDetail(id, display, true), workerId, workerCount);
                ctx.error("test-failure", message, label, exClass);
            }

            @Override
            public void onUserOutput(int workerId, String line) {
                // Muted by default; --verbose surfaces it. We hand the line to the
                // view via the step context — only:cli owns the actual streams.
                if (!verbose) return;
                String prefix = workerCount > 1 ? "[w" + workerId + "] " : "";
                if (!module.isEmpty()) prefix = "[" + module + "] " + prefix;
                ctx.output(prefix + line);
            }

            @Override
            public void onWarning(String code, String message) {
                ctx.warn(code == null || code.isBlank() ? "test" : code, message);
            }
        };
    }

    /** Progress / failure label: optional module and worker id. */
    static String progressLabel(String module, String display, int workerId, int workerCount) {
        String d = display == null ? "" : display;
        StringBuilder sb = new StringBuilder();
        if (module != null && !module.isBlank()) {
            sb.append(module).append(" :: ");
        }
        sb.append(d);
        if (workerCount > 1 && workerId > 0) {
            sb.append("  [w").append(workerId).append(']');
        }
        return sb.toString();
    }

    /**
     * Human detail for the live TUI / progress labels: simple class name, or Java-style {@code
     * Class.method(ParamType)} when both are known. Container starts (class-level) use the class
     * alone so the tree rotates through classes under a long Test phase. Never uses the JUnit {@code
     * " > "} display separator — the CLI paints this with Java syntax highlighting.
     */
    static String liveTestDetail(String uniqueId, String display, boolean isTest) {
        String cls = cc.jumpkick.test.JUnitLauncher.classFromUniqueId(uniqueId);
        String simple = simpleClassName(cls);
        String d = normalizeTestDisplay(display);
        if (!isTest) {
            // Class/container: prefer FQCN simple name; fall back to JUnit display name.
            if (!simple.isEmpty()) return simple;
            return d;
        }
        if (simple.isEmpty()) return d;
        if (d.isEmpty() || d.equals(simple)) return simple;
        // Already "FooTest.bar" / "FooTest.bar(Path)".
        if (d.startsWith(simple + ".") || d.startsWith(simple + "(")) return d;
        // Method-only display ("bar" / "bar(Path)") → Class.method(...).
        return simple + "." + d;
    }

    /**
     * Normalize a JUnit display name into a Java-ish member form: strips surrounding whitespace and
     * rewrites the common {@code "Class > method"} separator to a dot.
     */
    static String normalizeTestDisplay(String display) {
        if (display == null) return "";
        String d = display.trim();
        // JUnit Platform often uses "FooTest > bar" as a composite display.
        int sep = d.indexOf(" > ");
        if (sep > 0) {
            d = d.substring(0, sep).trim() + "." + d.substring(sep + 3).trim();
        }
        return d;
    }

    static String simpleClassName(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) return "";
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn.trim() : fqcn.substring(dot + 1).trim();
    }

    /**
     * Compile test sources with action-cache lookup. Mirrors the compile-main step: same task ID /
     * classpath / output-dir shape, and — crucially — the same {@code processorPath} + {@link
     * cc.jumpkick.task.JavaIncrementalCompile.ApSetup} wiring, so annotation processors (Lombok,
     * Immutables, …) run over test sources too. Modern javac only runs processors named by {@code
     * -processorpath}; without it, a test class using {@code @Getter} would fail to find its
     * generated modules even though main compilation handled the same annotation.
     */
    public static boolean compileWithCache(
            TaskContext ctx,
            String taskId,
            Path srcDir,
            Path outputDir,
            List<Path> classpath,
            List<Path> processorPath,
            int release,
            List<String> javacArgs,
            Path javaHome,
            cc.jumpkick.task.JavaIncrementalCompile.ApSetup ap,
            Cas cas,
            Path cacheRoot)
            throws IOException {

        List<Path> sources = CompileSupport.collectJavaSources(srcDir);
        if (sources.isEmpty()) {
            Files.createDirectories(outputDir);
            return true;
        }

        // Project-qualify so the `tasks/<taskId>` pointer is unique per module
        // (display labels keep the plain base name).
        String cacheTaskId = ActionKey.qualifiedTaskId(taskId, outputDir);
        CompileRequest request = CompileRequest.builder()
                .sources(sources)
                .classpath(classpath)
                .outputDir(outputDir)
                .release(release)
                .extraOptions(javacArgs)
                .javaHome(javaHome)
                .processorPath(processorPath)
                .build();
        // Action payloads live in the cache CAS; callers may pass the artifact CAS for classpath.
        ActionCache actionCache = new ActionCache(JkStores.cacheCas(cacheRoot), cacheRoot.resolve("actions"));
        boolean useCache = !cc.jumpkick.config.SessionContext.current().config().rebuildOr(false);
        java.nio.file.Path stateDir =
                cacheRoot.resolve("actions").resolve("incremental-java").resolve(cacheTaskId);

        // Reweight the bar slice from the real request: a CAS hit is a cheap
        // restore (3), else a full compile. Same key JavaIncrementalCompile uses.
        if (useCache) {
            try {
                boolean restores = actionCache
                        .lookup(ActionKey.forJavac(cacheTaskId, request, BuildIdentity.cacheKeyVersion()))
                        .isPresent();
                ctx.reweight(restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
            } catch (Exception ignored) {
                /* keep the up-front estimate */
            }
        }
        ctx.label(taskId + ": " + sources.size() + " sources");
        cc.jumpkick.task.JavaIncrementalCompile.Result r = cc.jumpkick.task.JavaIncrementalCompile.run(
                cacheTaskId,
                request,
                BuildIdentity.cacheKeyVersion(),
                useCache,
                actionCache.cas(),
                actionCache,
                stateDir,
                ap);
        // Surface javac diagnostics by severity — errors fail, warnings (e.g.
        // deprecation/unchecked) are shown but don't. Mirrors the main-compile
        // step so test sources report warnings the same way.
        boolean errored = false;
        for (CompileResult.Diagnostic d : r.diagnostics()) {
            if (d.severity() == CompileResult.Severity.ERROR) {
                ctx.error("javac", d.describe());
                errored = true;
            } else {
                ctx.warn("javac", d.describe());
            }
        }
        if (!r.success()) {
            // A failure must never be silent: if the compiler produced no ERROR
            // diagnostic (crash, swallowed output), say so explicitly.
            if (!errored) {
                ctx.error("javac", "test compile failed without compiler diagnostics (outcome: " + r.outcome() + ")");
            }
            return false;
        }
        if (r.cacheHit()) {
            ctx.label(taskId + ": cache hit " + r.actionKey().substring(0, 8));
            ctx.cached(); // SKIPPED — pure restore didWork accounting)
        } else {
            ctx.label(taskId + ": compiled " + sources.size() + " sources");
        }
        return true;
    }
}
