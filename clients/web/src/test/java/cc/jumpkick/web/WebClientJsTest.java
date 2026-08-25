// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The dashboard's headless JS suites — every {@code src/test/js/*.test.mjs} under {@code node
 * --test}, one suite per parameterized case. This is the only harness: the four per-module copies
 * it replaced each carried their own {@code nodeAvailable()} guard that turned a missing Node into
 * a silent green (JK-2441).
 *
 * <p><b>Node is mandatory.</b> Absent Node the class fails loudly. The single escape hatch is
 * {@code JK_WEB_JS_SKIP=1}, which CI never sets — a skip has to be asked for by name.
 *
 * <p>Staging: the SPA's shipped assets are copied once into a {@code type:module} temp dir (a bare
 * {@code .js} import is CommonJS, and every suite's top-level {@code import} would throw).
 * {@code JK_APP_DIR} names that directory, and a suite reaches any module or {@code index.html}
 * through it. Adding a module or a suite needs no change here.
 *
 * <p>Paths resolve against the module root, not {@code user.dir}: workspace builds run tests with
 * the engine CWD at {@code ~/.local/state/jk/engine}, so bare {@code src/...} relatives miss
 * {@code clients/web/...}, and classpath output may live under {@code <workspace>/target/clients/
 * web/} (Mill-style) rather than {@code clients/web/target/}.
 */
class WebClientJsTest {

    /** Opt out of the JS tier by name. Never set in CI; the tier fails without it. */
    private static final String SKIP_ENV = "JK_WEB_JS_SKIP";

    private static final Path WEB_ASSETS = Path.of("src/main/resources/web");

    /** The in-DOM Vue template and the SPA's single {@code <script type="module">} entry point. */
    private static final String SHELL = "index.html";

    private static final Path JS_SUITES = Path.of("src/test/js");

    /** Node's TAP epilogue: {@code # tests 94}, {@code # fail 0}. */
    private static final Pattern TAP_COUNT = Pattern.compile("(?m)^# (tests|fail) (\\d+)$");

    /** A suite's own {@code test('…', …)} declarations, at any nesting. */
    private static final Pattern TEST_DECL = Pattern.compile("^\\s*test\\(");

    @TempDir
    static Path stage;

    /** {@code JK_*} paths into {@link #stage}, one entry per staged SPA module. */
    private static Map<String, String> nodeEnv;

    @BeforeAll
    static void requireNodeAndStageTheSpa() throws IOException {
        if ("1".equals(System.getenv(SKIP_ENV))) {
            Assumptions.abort(SKIP_ENV + "=1 — the JS suites were skipped on request");
        }
        if (!nodeAvailable()) {
            throw new AssertionError("`node` is not on PATH, and the dashboard's JS suites are part of "
                    + "the gate. Install Node (CI uses actions/setup-node), or skip this tier "
                    + "deliberately with " + SKIP_ENV + "=1.");
        }

        Path assets = moduleRoot().resolve(WEB_ASSETS);
        for (Path module : filesEndingIn(assets, ".js")) {
            Files.copy(module, stage.resolve(module.getFileName().toString()));
        }
        // The shell too: shell.test.mjs reads it to check that index.html's one module entry point
        // reaches every file the dashboard ships, and calls nothing the root component dropped.
        Files.copy(assets.resolve(SHELL), stage.resolve(SHELL));
        Files.writeString(stage.resolve("package.json"), "{\"type\":\"module\"}\n");
        nodeEnv = Map.of("JK_APP_DIR", stage.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jsSuites")
    void js_suite_passes(String name, Path suite) throws Exception {
        String output = runNode(suite);
        Map<String, Integer> counts = tapCounts(output);
        // `fail 0` is also what a suite reports when it ran nothing: an empty file, an import that
        // died, an early exit. Hold the run to the file's own declarations — every `test(...)` in
        // the source has to show up in node's count, or the green is an artifact.
        long declared = declaredTests(suite);
        assertThat(declared).as("%s declares no test(...) cases", name).isPositive();
        assertThat(counts.get("tests"))
                .as("%s declares %d test(...) cases; node ran fewer%n%s", name, declared, output)
                .isNotNull()
                .isGreaterThanOrEqualTo((int) declared);
        assertThat(counts.get("fail")).as("%s%n%s", name, output).isZero();
    }

    /**
     * Regression: the fold layer normalizes the wire's {@code task} vocabulary back to {@code
     * step}/{@code steps}, and a component reading the FOLDED model must use the folded names or it
     * silently renders empty (Vue resolves an unknown field to undefined). Scanned across every
     * module rather than one file, so moving a template between modules cannot mute the check.
     */
    @Test
    void components_read_the_folded_vocabulary() throws Exception {
        String spa = concatModules();
        assertThat(spa).contains("openPhase.steps").contains("mod.steps").contains("d.step)");
        assertThat(spa)
                .doesNotContain("openPhase.tasks")
                .doesNotContain("mod.tasks")
                .doesNotContain("d.task)");
    }

    /** Every shipped {@code .js} module, concatenated — for the checks that are about the SPA, not a file. */
    private static String concatModules() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path module : filesEndingIn(moduleRoot().resolve(WEB_ASSETS), ".js")) {
            all.append(Files.readString(module)).append('\n');
        }
        return all.toString();
    }

    /**
     * The history table's Tests column renders from the journal's nested {@code tests} object — the
     * one wire spelling of a test count (JK-2424). The SPA cannot import Java, so it hand-types every
     * field name; naming the retired flat spellings here is what stops one creeping back into a
     * template, where Vue resolves an unknown field to undefined and the column silently shows an
     * em dash forever.
     */
    @Test
    void the_history_table_reads_the_nested_test_counts() throws Exception {
        String html = Files.readString(moduleRoot().resolve(WEB_ASSETS).resolve("index.html"));

        assertThat(html).contains("r.tests.succeeded").contains("r.tests.total");
        for (String retired : List.of(
                "testsFailed",
                "testFailed",
                "testsTotal",
                "testTotal",
                "testsSucceeded",
                "testSucceeded",
                "testsSkipped",
                "testSkipped")) {
            assertThat(html)
                    .as("index.html must not read the retired flat spelling %s", retired)
                    .doesNotContain(retired);
        }
    }

    /** One case per {@code *.test.mjs}, discovered — a new suite is picked up by existing. */
    static Stream<Object[]> jsSuites() throws IOException {
        List<Path> suites = filesEndingIn(moduleRoot().resolve(JS_SUITES), ".test.mjs");
        if (suites.isEmpty()) {
            throw new IOException("no *.test.mjs under " + moduleRoot().resolve(JS_SUITES));
        }
        return suites.stream().map(p -> new Object[] {p.getFileName().toString(), p});
    }

    /** Runs one suite with the TAP reporter (deterministic to parse, unlike the TTY-sensitive default). */
    private static String runNode(Path suite) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                        "node",
                        "--test",
                        "--test-reporter=tap",
                        suite.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .directory(stage.toFile());
        builder.environment().putAll(nodeEnv);
        Process node = builder.start();
        String output = new String(node.getInputStream().readAllBytes());
        assertThat(node.waitFor(60, TimeUnit.SECONDS))
                .as("node --test %s finished%n%s", suite.getFileName(), output)
                .isTrue();
        assertThat(node.exitValue())
                .as("node --test %s exit status%n%s", suite.getFileName(), output)
                .isZero();
        return output;
    }

    /** {@code test(...)} cases the suite source declares — the floor node's run has to clear. */
    private static long declaredTests(Path suite) throws IOException {
        try (Stream<String> lines = Files.lines(suite)) {
            return lines.filter(l -> TEST_DECL.matcher(l).find()).count();
        }
    }

    private static Map<String, Integer> tapCounts(String output) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher m = TAP_COUNT.matcher(output);
        while (m.find()) {
            counts.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        return counts;
    }

    private static List<Path> filesEndingIn(Path dir, String suffix) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Module root containing {@code src/main/resources/web/fold.js}. Prefers cwd when already in
     * the module; otherwise {@code clients/web} under a workspace root; else maps classpath output
     * under {@code target/<module-rel>/} back to the source module, then walks ancestors.
     */
    static Path moduleRoot() throws IOException {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (isWebModuleRoot(cwd)) return cwd;
        Path nested = cwd.resolve("clients/web");
        if (isWebModuleRoot(nested)) return nested.normalize();

        // Classpath resource: standalone layout (…/clients/web/target/classes/…/fold.js) or
        // workspace layout (…/target/clients/web/classes/…/fold.js).
        URL res = WebClientJsTest.class.getResource("/web/fold.js");
        if (res != null && "file".equals(res.getProtocol())) {
            try {
                Path resourceFile = Path.of(res.toURI()).toAbsolutePath().normalize();
                Path fromOutput = moduleRootFromOutputPath(resourceFile);
                if (fromOutput != null) return fromOutput;
                for (Path d = resourceFile.getParent(); d != null; d = d.getParent()) {
                    if (isWebModuleRoot(d)) return d;
                }
            } catch (URISyntaxException ignored) {
                // fall through
            }
        }

        for (Path d = cwd; d != null; d = d.getParent()) {
            Path web = d.resolve("clients/web");
            if (isWebModuleRoot(web)) return web.normalize();
            if (isWebModuleRoot(d)) return d;
        }
        throw new IOException("cannot locate jk-web module root from cwd=" + cwd);
    }

    /**
     * Map a path under a build output tree back to the source module root.
     *
     * <ul>
     *   <li>{@code <ws>/target/<module-rel>/…} → {@code <ws>/<module-rel>} (workspace layout)
     *   <li>{@code <module>/target/…} → {@code <module>} (standalone layout)
     * </ul>
     */
    static Path moduleRootFromOutputPath(Path somewhereUnderOutput) {
        Path abs = somewhereUnderOutput.toAbsolutePath().normalize();
        for (Path d = abs; d != null; d = d.getParent()) {
            if (d.getFileName() == null || !"target".equals(d.getFileName().toString())) continue;
            Path parentOfTarget = d.getParent();
            if (parentOfTarget == null) continue;

            // Standalone: <module>/target/…
            if (isWebModuleRoot(parentOfTarget)) return parentOfTarget.normalize();

            // Workspace: <ws>/target/<module-rel>/… — try successive path prefixes under <ws>.
            try {
                Path rel = d.relativize(abs);
                int n = rel.getNameCount();
                for (int i = 1; i <= n; i++) {
                    Path candidate = parentOfTarget.resolve(rel.subpath(0, i));
                    if (isWebModuleRoot(candidate)) return candidate.normalize();
                }
            } catch (IllegalArgumentException ignored) {
                // different roots — keep walking
            }
        }
        return null;
    }

    private static boolean isWebModuleRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("jk.toml"))
                && Files.isRegularFile(dir.resolve("src/main/resources/web/fold.js"))
                && Files.isRegularFile(dir.resolve("src/test/js/fold.test.mjs"));
    }
}
