// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.TestFailureSource;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.TestProgressListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Test-step building blocks shared by the build plan and the {@code test} command. Coupled only
 * to {@link TaskContext} (the view-agnostic progress callback), so it lives in {@code :runtime}
 * and embedders can drive it without the CLI/TUI.
 */
public final class TestSupport {

    /**
     * Sources under {@code [test] extra-src}, by extension. These roots belong to the test tier but
     * to no suite: there is nothing in them to run, so they compile with whichever suites were
     * selected rather than being selectable themselves. Sibling-consumed helpers use
     * {@code [test] fixtures} instead.
     */
    static List<Path> testExtraSources(JkBuild project, Path moduleDir, String ext) throws IOException {
        List<String> roots = project.build().testExtraSrc();
        if (roots.isEmpty()) return List.of();
        List<Path> out = new ArrayList<>();
        for (String rel : roots) {
            Path root = moduleDir.resolve(rel).normalize();
            if (Files.isDirectory(root)) {
                out.addAll(TestSuites.collectExt(root, ext));
            } else if (rel.endsWith(ext) && Files.isRegularFile(root)) {
                // One file, not a root. `clients/cli` needs exactly one source out of the IntelliJ
                // plugin's package — JkWireModel, the wire parser, which imports only java.util and
                // org.jetbrains.annotations. Its five neighbours need the platform SDK, so naming
                // the directory would not compile. Gradle expresses the same thing by Sync-ing that
                // one file into a generated source dir.
                out.add(root);
            }
        }
        return out;
    }

    private TestSupport() {}

    private static final Pattern TEST_ANNOTATION_REGEX =
            Pattern.compile("@(?:Test|ParameterizedTest|TestFactory|TestTemplate|RepeatedTest)\\b");

    /**
     * Best-effort count of JUnit test methods under {@code testSrcDir} — scans {@code .java}/{@code
     * .kt} sources for {@code @Test}-family annotations. Feeds the build's {@code estimatedTestCount}
     * (progress-bar weighting); a zero estimate falls back to a flat bar. Never throws.
     */
    public static int estimateTestCount(Path testSrcDir) {
        if (!Files.isDirectory(testSrcDir)) return 0;
        int count = 0;
        for (Path file : testSources(testSrcDir)) {
            try {
                String content = Files.readString(file);
                count += (int) TEST_ANNOTATION_REGEX.matcher(content).results().count();
            } catch (IOException ignored) {
                // best-effort: skip unreadable files, keep counting
            }
        }
        return count;
    }

    /**
     * Count test methods across every discovered suite, not the default suite only. Dedupes when
     * java/kotlin roots share a directory (SIMPLE layout).
     */
    public static int estimateAllSuiteTestCount(Path moduleDir, boolean compact) {
        int total = 0;
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (String suite : TestSuites.discover(moduleDir, compact)) {
            roots.addAll(TestSuites.javaRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.kotlinRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.groovyRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.scalaRoots(moduleDir, compact, suite));
        }
        for (Path r : roots) total += estimateTestCount(r);
        return total;
    }

    /**
     * Count test methods for the suites a SELECTION will actually run ({@link #selectedSuites}):
     * sizing the bar/ETA with every discovered suite makes a plain {@code jk test} under-fill and
     * snap to 100 when an integration suite exists.
     */
    public static int estimateSelectedSuiteTestCount(Path moduleDir, boolean compact, TestSelection selection) {
        int total = 0;
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (String suite : selectedSuites(moduleDir, compact, selection)) {
            roots.addAll(TestSuites.javaRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.kotlinRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.groovyRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.scalaRoots(moduleDir, compact, suite));
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
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (String suite : TestSuites.discover(moduleDir, compact)) {
            roots.addAll(TestSuites.javaRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.kotlinRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.groovyRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.scalaRoots(moduleDir, compact, suite));
        }
        for (Path r : roots) total += estimateTestClassCount(r);
        return total;
    }

    /** Source files under {@code testSrcDir} that contain at least one JUnit test annotation. */
    public static int estimateTestClassCount(Path testSrcDir) {
        if (!Files.isDirectory(testSrcDir)) return 0;
        int count = 0;
        for (Path file : testSources(testSrcDir)) {
            try {
                String content = Files.readString(file);
                if (TEST_ANNOTATION_REGEX.matcher(content).find()) count++;
            } catch (IOException ignored) {
                // best-effort
            }
        }
        return count;
    }

    private static List<Path> testSources(Path testSrcDir) {
        // One enumeration answers all four languages; a single pass cannot repeat a file.
        return InputTrees.of(testSrcDir).withExtensions(".java", ".kt", ".groovy", ".scala");
    }

    /** Collect all test sources for every discovered suite (deduped paths). */
    public static List<Path> collectAllSuiteTestSources(Path moduleDir, boolean compact) throws IOException {
        return collectSuiteTestSources(moduleDir, compact, TestSuites.discover(moduleDir, compact));
    }

    /**
     * The suites a SELECTION will actually run in {@code moduleDir} — the set compile-test's key is
     * derived from, via {@link PlannerTest.TestSources#collect}.
     *
     * <p>{@link #collectAllSuiteTestSources} is the estimate-side answer, every suite on disk, which
     * is right for a count and wrong for a key: the build compiles the selection and nothing else,
     * and {@link TestSelection#DEFAULT} is the {@code test} suite alone. An unresolvable selection
     * falls back to every discovered suite, the same rule {@link #estimateSelectedSuiteTestCount}
     * uses.
     */
    public static List<String> selectedSuites(Path moduleDir, boolean compact, @Nullable TestSelection selection) {
        List<String> discovered = TestSuites.discover(moduleDir, compact);
        if (selection != null) {
            var resolved = selection.resolve(discovered);
            if (resolved.ok()) return resolved.suites();
        }
        return discovered;
    }

    private static List<Path> collectSuiteTestSources(Path moduleDir, boolean compact, List<String> suites)
            throws IOException {
        // Fall back to the default suite's dirs even when empty of sources, so compile-test no-ops
        // cleanly rather than being forecast against nothing at all.
        List<String> eff = suites.isEmpty() ? List.of(TestSuites.DEFAULT) : suites;
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        out.addAll(TestSuites.collectJavaSources(moduleDir, compact, eff));
        out.addAll(TestSuites.collectKotlinSources(moduleDir, compact, eff));
        out.addAll(TestSuites.collectGroovySources(moduleDir, compact, eff));
        out.addAll(TestSuites.collectScalaSources(moduleDir, compact, eff));
        return new ArrayList<>(out);
    }

    /**
     * Plain-text failure report for the CLI to paint. Layout (no ANSI — the client styles it):
     *
     * <pre>
     * Test Failure
     * module: group:artifact
     * 1 test failed
     *
     * FAILED SimpleClass.method
     *
     * [assertj description]
     * expected: "42"
     *  but was: "41"
     *
     * @@source path=… line=N start=S lang=java
     * @@src …
     * @@src-end
     * › AssertionFailedError thrown at line 23
     * </pre>
     *
     * <p>Simple class/method names only (no package FQCNs; params stay as simple type names). When {@code moduleDir}
     * is set, a 7-line source snippet is resolved from the stack. The leading {@code Test Failure}
     * title and trailing {@code Test Failure end} are sentinels the CLI rewrites into a red pill +
     * header / rail (the closer keeps a blank line inside the assertion body from ending the paint).
     */
    public static List<String> renderFailures(TestSummary result) {
        return renderFailures(result, null);
    }

    /** As {@link #renderFailures(TestSummary)} with module-dir source resolution. */
    public static List<String> renderFailures(TestSummary result, @Nullable Path moduleDir) {
        return renderFailures(result, moduleDir, null);
    }

    /** Share {@code cache} with {@link #bridgeListener} so each failure is resolved once. */
    public static List<String> renderFailures(
            TestSummary result, @Nullable Path moduleDir, TestFailureSource.@Nullable Cache cache) {
        List<String> out = new ArrayList<>();
        List<TestFailureInfo> failures = result.failures();
        if (failures.isEmpty()) return out;
        TestFailureSource.Cache effectiveCache = cache != null ? cache : new TestFailureSource.Cache();
        // No leading blank — the CLI leaves a single blank under the prompt / live region.
        out.add("Test Failure");
        // First non-blank module wins for the header (multi-module reports still list each FAILED).
        String module = failures.stream()
                .map(TestFailureInfo::module)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse("");
        if (!module.isBlank()) out.add("module: " + module);
        out.add(failures.size() + " test" + (failures.size() == 1 ? "" : "s") + " failed");
        for (TestFailureInfo f : failures) {
            out.add("");
            out.add("FAILED " + shortTestLabel(f));
            Optional<TestFailureSource.Snippet> snippet = Optional.empty();
            if (moduleDir != null) {
                snippet = effectiveCache.resolve(moduleDir, f.className(), f.stack());
            }
            // Assertion body, then source snippet, then exception locus under the snippet.
            List<String> body = failureBodyLines(f);
            if (!body.isEmpty()) {
                out.add("");
                out.addAll(body);
            }
            if (snippet.isPresent()) {
                out.add("");
                out.addAll(TestFailureSource.encodeMarkers(snippet.get()));
                String ex = simpleTypeName(f.exceptionClass());
                if (ex.isEmpty()) ex = "Error";
                out.add("    " + ex + " thrown at line " + snippet.get().errorLine());
            } else if (!f.exceptionClass().isEmpty()) {
                out.add("");
                out.add("    " + simpleTypeName(f.exceptionClass()));
                List<String> frames = failureStackFrames(f);
                if (!frames.isEmpty()) {
                    out.add("");
                    out.addAll(frames);
                }
            } else {
                List<String> frames = failureStackFrames(f);
                if (!frames.isEmpty()) {
                    out.add("");
                    out.addAll(frames);
                }
            }
        }
        // Closer so the CLI does not treat a blank inside the assertion body as end-of-report.
        out.add("Test Failure end");
        return out;
    }

    /**
     * {@code SimpleClass.method()} / {@code SimpleClass.method(Path)} — no package FQCN; keep
     * parentheses, simple param type names, and a trailing invocation tag ({@code [#1]}). Falls
     * back to the failure's test name when class/method are unknown.
     */
    static String shortTestLabel(TestFailureInfo f) {
        String cls = simpleClassName(f.className());
        String method = f.method() == null ? "" : f.method().strip();
        // "Foo > bar()" / "Foo.bar()" → method part only
        int gt = method.lastIndexOf(" > ");
        if (gt >= 0) method = method.substring(gt + 3).strip();
        // Prefer simple param forms: (java.nio.file.Path) → (Path)
        method = simplifyMethodParams(method);
        // If method still looks like Class.method, split — but not Foo(Path) where '(' is params.
        int paren = method.indexOf('(');
        int dot = method.lastIndexOf('.');
        if (dot > 0 && (paren < 0 || dot < paren)) {
            String maybeCls = method.substring(0, dot);
            String maybeM = method.substring(dot + 1);
            if (cls.isEmpty()) cls = simpleClassName(maybeCls);
            method = maybeM;
        }
        method = simplifyMethodParams(method);
        // Bare name with no parens (rare) — add empty () for consistency with JUnit display.
        if (!method.isEmpty() && method.indexOf('(') < 0 && !method.equals("(test run)")) {
            method = method + "()";
        }
        if (cls.isEmpty() && method.isEmpty()) return "?";
        String label = cls.isEmpty() ? method : (method.isEmpty() ? cls : cls + "." + method);
        return TestFailureInfo.label("", label, f.worker());
    }

    /**
     * Keep {@code (…)} but strip package prefixes inside params: {@code (java.nio.file.Path)} →
     * {@code (Path)}; leave {@code ()} alone.
     */
    static String simplifyMethodParams(String method) {
        if (method == null || method.isEmpty()) return "";
        int open = method.indexOf('(');
        int close = method.lastIndexOf(')');
        if (open < 0 || close <= open) return method.strip();
        String name = method.substring(0, open).strip();
        String inside = method.substring(open + 1, close).strip();
        String suffix = method.substring(close + 1);
        if (inside.isEmpty()) return name + "()" + suffix;
        StringBuilder simplified = new StringBuilder();
        for (String part : inside.split(",")) {
            String p = part.strip();
            int d = p.lastIndexOf('.');
            if (d >= 0) p = p.substring(d + 1);
            if (!simplified.isEmpty()) simplified.append(", ");
            simplified.append(p);
        }
        return name + "(" + simplified + ")" + suffix;
    }

    /** {@code org.opentest4j.AssertionFailedError} → {@code AssertionFailedError}. */
    static String simpleTypeName(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) return "";
        int d = fqcn.lastIndexOf('.');
        return d >= 0 ? fqcn.substring(d + 1) : fqcn;
    }

    /** Human-facing assertion / message lines (no stack frames, no exception FQCN prefix). */
    static List<String> failureBodyLines(TestFailureInfo f) {
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
        String details = f.stack() == null ? "" : f.stack();
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
    static List<String> failureStackFrames(TestFailureInfo f) {
        String details = f.stack() == null ? "" : f.stack();
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
        return bridgeListener(ctx, workerCount, verbose, "", null);
    }

    /**
     * As {@link #bridgeListener(TaskContext, int, boolean)} with a module coord for labels / failure
     * diagnostics.
     */
    public static TestProgressListener bridgeListener(
            TaskContext ctx, int workerCount, boolean verbose, String moduleLabel) {
        return bridgeListener(ctx, workerCount, verbose, moduleLabel, null);
    }

    /**
     * Full bridge: module coord + project dir so source snippets attach to structured test-failure
     * diagnostics (web Activity / details.jsonl).
     */
    public static TestProgressListener bridgeListener(
            TaskContext ctx, int workerCount, boolean verbose, @Nullable String moduleLabel, @Nullable Path moduleDir) {
        return bridgeListener(ctx, workerCount, verbose, moduleLabel, moduleDir, null);
    }

    /** As {@link #bridgeListener(TaskContext, int, boolean, String, Path)} with a shared snippet cache. */
    public static TestProgressListener bridgeListener(
            TaskContext ctx,
            int workerCount,
            boolean verbose,
            @Nullable String moduleLabel,
            @Nullable Path moduleDir,
            TestFailureSource.@Nullable Cache cache) {
        String module = moduleLabel == null ? "" : moduleLabel.trim();
        Path dir = moduleDir;
        TestFailureSource.Cache snippets = cache != null ? cache : new TestFailureSource.Cache();
        return new TestProgressListener() {
            @Override
            public void onDiscoveryTotal(int classes, int tests) {
                if (tests > 0) {
                    ctx.label(progressLabel(module, "running " + tests + " tests", 0, 1));
                }
            }

            @Override
            public void onTestStarted(String id, String display, boolean isTest, int workerId) {
                // Label at start so long-running tests/classes show as "current work" in the TUI
                // tree (finish-only labels lag one event behind). Prefer Class > method when the
                // unique id carries a class segment and display is the bare method name.
                // Skip engine/suite roots (no [class:…] segment) so we don't flash "JUnit Jupiter".
                if (!isTest && JUnitLauncher.classFromUniqueId(id).isEmpty()) {
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
                // Code "test-failure" (not "test") marks a per-test failure that is
                // already shown in full by the run-tests renderFailures block. The
                // diagnostic still flows to JSON consumers (details.jsonl / --output json),
                // but human listeners suppress the text banner so the same failure isn't
                // printed twice. Test *infra* errors keep code "test" and still surface.
                String methodLabel = method != null && !method.isBlank() ? method : label;
                String file = "";
                int line = 0;
                int snippetStart = 0;
                List<String> snippetLines = List.of();
                if (dir != null) {
                    var snip = snippets.resolve(dir, className, stack);
                    if (snip.isPresent()) {
                        var s = snip.get();
                        file = s.relativePath();
                        line = s.errorLine();
                        snippetStart = s.startLine();
                        snippetLines = s.lines();
                    }
                }
                ctx.error(
                        "test-failure",
                        message,
                        new TestFailureInfo(
                                module == null ? "" : module,
                                engine == null ? "" : engine,
                                className == null ? "" : className,
                                methodLabel == null ? "" : methodLabel,
                                exClass == null ? "" : exClass,
                                message == null ? "" : message,
                                stack == null ? "" : stack,
                                workerId,
                                file,
                                line,
                                snippetStart,
                                snippetLines));
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

    /**
     * Progress / failure label: optional module and worker id. Rendered by
     * {@link TestFailureInfo#label} — the one owner of the {@code " :: "} separator — with the
     * worker suffix suppressed for a serial run, where {@code [w1]} would say nothing.
     */
    static String progressLabel(String module, String display, int workerId, int workerCount) {
        return TestFailureInfo.label(module, display, workerCount > 1 ? workerId : 0);
    }

    /**
     * Human detail for the live TUI / progress labels: simple class name, or Java-style {@code
     * Class.method(ParamType)} when both are known. Container starts (class-level) use the class
     * alone so the tree rotates through classes under a long Test phase. Never uses the JUnit {@code
     * " > "} display separator — the CLI paints this with Java syntax highlighting.
     */
    static String liveTestDetail(String uniqueId, String display, boolean isTest) {
        String cls = JUnitLauncher.classFromUniqueId(uniqueId);
        String simple = simpleClassName(cls);
        String d = normalizeTestDisplay(display);
        // Display never shows package FQCNs in param lists (wire may still carry them).
        d = simplifyMethodParams(d);
        if (!isTest) {
            // Class/container: prefer FQCN simple name; fall back to JUnit display name.
            if (!simple.isEmpty()) return simple;
            return simpleClassName(d.isEmpty() ? "" : d);
        }
        if (simple.isEmpty()) return d;
        if (d.isEmpty() || d.equals(simple)) return simple;
        // Already "FooTest.bar" / "FooTest.bar(Path)" (params already simplified).
        if (d.startsWith(simple + ".") || d.startsWith(simple + "(")) return d;
        // Method-only display ("bar" / "bar(Path)" / FQCN params) → Class.method(...).
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
     * classpath / output-dir shape, and the same {@code processorPath} + Zinc worker, so annotation
     * processors (Lombok, Immutables, …) run over test sources too. The request is {@link
     * PlannerCompile#testCompileRequest}'s, the same body the forecast keys from.
     */
    public static boolean compileWithCache(
            TaskContext ctx,
            String taskId,
            PlannerCompile.TestCompile compile,
            Path generatedSourceDir,
            Cas cas,
            Path cacheRoot,
            WorkerEnv env)
            throws IOException {
        List<Path> sources = compile.sources();
        Path outputDir = compile.outputDir();
        if (sources.isEmpty()) {
            Files.createDirectories(outputDir);
            return true;
        }

        // Project-qualify so the `tasks/<taskId>` pointer is unique per module
        // (display labels keep the plain base name).
        String cacheTaskId = ActionKey.qualifiedTaskId(taskId, outputDir);
        CompileRequest request = PlannerCompile.testCompileRequest(compile);
        // Action payloads live in the cache CAS; callers may pass the artifact CAS for classpath.
        ActionCache actionCache = new ActionCache(JkStores.cacheCas(cacheRoot), CacheTree.ACTIONS.under(cacheRoot));
        boolean useCache = !SessionContext.current().config().rebuildOr(false);
        Path actions = CacheTree.ACTIONS.under(cacheRoot);
        Path stateDir = ActionTree.INCREMENTAL_JAVA.under(actions).resolve(cacheTaskId);

        // Reweight the bar slice from the real request: a CAS hit is a cheap
        // restore (3), else a full compile. Same key JavaCompile uses.
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
        if (Perf.enabled() && TaskNames.COMPILE_TEST.equals(taskId)) {
            Perf.note(
                    "live-compile-test " + outputDir,
                    "key",
                    ActionKey.forJavac(cacheTaskId, request, BuildIdentity.cacheKeyVersion()),
                    "cp",
                    request.classpath().size(),
                    "src",
                    sources.size(),
                    "pp",
                    compile.processorPath().size(),
                    "release",
                    compile.release(),
                    "javaHome",
                    compile.javaHome(),
                    "out",
                    outputDir);
        }
        ctx.label(taskId + ": " + sources.size() + " sources");
        Path gen = generatedSourceDir != null
                ? generatedSourceDir
                : CacheTree.GENERATED.under(cacheRoot).resolve(cacheTaskId);
        Files.createDirectories(gen);
        Path workerJar = PluginJar.JAVA_COMPILER.locate(cas);
        JavaCompile.Result r = JavaCompile.run(
                cacheTaskId,
                request,
                BuildIdentity.cacheKeyVersion(),
                useCache,
                actionCache.cas(),
                actionCache,
                stateDir,
                workerJar,
                gen,
                env);
        ctx.waited(Duration.ofMillis(r.waitMillis()));
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
