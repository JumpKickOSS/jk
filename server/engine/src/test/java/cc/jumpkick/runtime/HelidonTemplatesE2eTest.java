// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.workspace.ExecPlans;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The {@code helidon/quickstart}, {@code helidon/declarative} and {@code helidon/database} templates
 * as {@code jk new} writes them, with no edit and no pin: the scaffold locks against the Helidon BOM, compiles on JDK 27
 * (Declarative's generators included), passes its own tests under the test config profile, and the
 * {@code <name>.jar} of its libs layout serves {@code /greet}. The defaults and the answers that
 * change the sources (Helidon JSON, tracing, the simple layout, a jlink image) are each built once;
 * every pairwise vector is the slow tier's {@code TemplateCatalogE2eTest}.
 *
 * <p>Maven Central and a JDK 27 (installed when the host has none); the CAS persists under the
 * test caches so repeat runs are warm.
 */
// Out of the unit tier: network resolve of the Helidon BOM, JDK 27 forks, a server per case.
@Tag("integration")
class HelidonTemplatesE2eTest {

    private static final Path ROOT = RepoRoot.find(HelidonTemplatesE2eTest.class);
    private static final Path TEMPLATES = ROOT.resolve("plugins/helidon/src/main/resources/templates/java/helidon");
    private static final Pattern PORT = Pattern.compile("(?:localhost|0\\.0\\.0\\.0|127\\.0\\.0\\.1):(\\d+)");

    static Stream<Arguments> scaffolds() {
        return Stream.of(
                Arguments.of("quickstart", null, Map.of()),
                Arguments.of("quickstart", null, Map.of("media", "json", "tracing", "true", "jlink", "true")),
                Arguments.of("declarative", null, Map.of()),
                Arguments.of(
                        "declarative",
                        "simple",
                        Map.of("media", "json", "tracing", "true", "health", "false", "generate_main", "true")),
                Arguments.of("database", null, Map.of()),
                // Built and tested on the in-memory H2 the tests use; served only where the
                // application's database is in the jar.
                Arguments.of("database", "simple", Map.of("db", "mongodb", "health", "false", "jlink", "true")));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("scaffolds")
    void the_scaffold_locks_builds_tests_and_serves_greet(
            String template, @Nullable String layout, Map<String, String> params, @TempDir Path tmp) throws Exception {
        SchemaPresetExamplesTest.workerJarFromWorkspace(PluginJar.HELIDON, "plugins/helidon");
        Path tree = TEMPLATES.resolve(template + ".g8");
        Path project = NewProjectOps.create(new NewProjectOps.Request(
                        "demo",
                        tmp.toString(),
                        "com.acme",
                        null,
                        layout,
                        tree.toString(),
                        true,
                        params,
                        false,
                        null,
                        tree.toString()))
                .path();
        String manifest = Files.readString(project.resolve("jk.toml"));
        assertThat(manifest).contains("java = 27").contains("[helidon]\nversion = \"27.0.1\"");

        Path cache = TestCaches.dir("helidon-quickstart-cache");
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).as("lock").isEmpty();
        assertThat(lock.success()).as("lock").isTrue();

        BuildPlan plan = BuildPlanner.fullPlan(new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                1,
                null,
                null,
                false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current()));
        BuildPlanResult built = plan.run();
        assertThat(built.errors()).as("build").isEmpty();
        assertThat(built.success()).as("build").isTrue();
        TestSummary tests = plan.get(BuildPlanner.TEST_RESULT).orElseThrow();
        assertThat(tests.failed()).as("failed tests").isZero();
        assertThat(tests.succeeded()).as("the template's MainTest").isGreaterThanOrEqualTo(3);

        Path target = project.resolve("target");
        assertThat(target.resolve("demo.jar")).isRegularFile();
        assertThat(target.resolve("libs")).isDirectory();
        if ("true".equals(params.get("jlink"))) {
            assertThat(target.resolve("demo-jri/bin/start")).isRegularFile();
        }
        if (!"h2".equals(params.getOrDefault("db", "h2"))) return; // a database server the host lacks
        assertThat(serve(project, cache, target, "/greet")).contains("\"message\":\"Hello World!\"");
        if ("database".equals(template)) {
            assertThat(serve(project, cache, target, "/db/type")).contains("\"name\":\"Normal\"");
        }
    }

    /**
     * {@code java -jar demo.jar} from {@code dir} on the JDK {@code jk run} picks for the module, on
     * a free port; the response to {@code path} once the server says where it listens.
     */
    private static String serve(Path project, Path cache, Path dir, String path) throws Exception {
        ExecPlan run = ExecPlans.execPlan(project, cache, "run", null, null);
        assertThat(run.error()).isNull();
        String java = run.argv().getFirst();
        Process p = new ProcessBuilder(java, "-Dserver.port=0", "-jar", "demo.jar")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuilder log = new StringBuilder();
        try {
            CompletableFuture<Integer> port = CompletableFuture.supplyAsync(() -> {
                try (BufferedReader out =
                        new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        synchronized (log) {
                            log.append(line).append('\n');
                        }
                        Matcher m = PORT.matcher(line);
                        if (m.find() && !m.group(1).equals("0")) return Integer.valueOf(m.group(1));
                    }
                    return null;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            Integer listening = port.get(90, TimeUnit.SECONDS);
            assertThat(listening).as("the server's port in:\n" + log).isNotNull();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listening + path))
                                    .timeout(Duration.ofSeconds(30))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return response.body();
        } finally {
            p.destroy();
            if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
        }
    }
}
