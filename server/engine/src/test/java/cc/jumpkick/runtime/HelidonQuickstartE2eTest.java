// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.PathUtil;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Helidon's own quickstarts under {@code [helidon] version} alone, on JDK 27: the lock resolves the
 * Helidon BOM's versions for short-name dependencies with no pins, the contributed processors
 * compile the inject quickstart's {@code @Service} types (main and test) and the binding {@code
 * Main} references, the suites run under the test config profile, and the {@code <name>.jar} the
 * libs layout writes serves {@code /greet} with {@code java -jar}.
 *
 * <p>Maven Central and a JDK 27 (installed when the host has none); the CAS persists under the
 * test caches so repeat runs are warm.
 */
// Out of the unit tier: network resolve of the Helidon BOM, JDK 27 forks.
@Tag("integration")
class HelidonQuickstartE2eTest {

    private static final Path ROOT = RepoRoot.find(HelidonQuickstartE2eTest.class);

    private static final Pattern PORT = Pattern.compile("(?:localhost|0\\.0\\.0\\.0|127\\.0\\.0\\.1):(\\d+)");

    @Test
    void the_se_quickstart_locks_builds_tests_and_runs_from_its_libs_layout(@TempDir Path tmp) throws Exception {
        Path project = fixture(tmp, "quickstart-se");
        Path cache = TestCaches.dir("helidon-quickstart-cache");
        lock(project, cache);
        String lock = Files.readString(project.resolve("jk-lock.toml"));
        assertThat(lock)
                .contains("io.helidon:helidon-dependencies")
                .contains("io.helidon.webserver:helidon-webserver:jar:\"\nversion  = \"27.0.1\"")
                .as("the codegen processors are pinned as closures")
                .contains("io.helidon.codegen:helidon-codegen-apt:27.0.1")
                .contains("io.helidon.service:helidon-service-codegen:27.0.1");

        BuildPlan plan = plan(project, cache);
        BuildPlanResult built = plan.run();
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();
        TestSummary tests = plan.get(BuildPlanner.TEST_RESULT).orElseThrow();
        assertThat(tests.failed()).isZero();
        assertThat(tests.succeeded())
                .as("MainTest's four and ConfigProfileTest, which reads helidon.config.profile=test")
                .isEqualTo(5);

        Path target = project.resolve("target");
        try (JarFile jar = new JarFile(target.resolve("quickstart-se.jar").toFile())) {
            Attributes main = jar.getManifest().getMainAttributes();
            assertThat(main.getValue(Attributes.Name.MAIN_CLASS)).isEqualTo("io.helidon.examples.quickstart.se.Main");
            assertThat(main.getValue(Attributes.Name.CLASS_PATH)).contains("libs/helidon-webserver-27.0.1.jar");
        }
        assertThat(target.resolve("libs/helidon-webserver-27.0.1.jar")).isRegularFile();

        Served served = serve(project, cache, target, "quickstart-se.jar", "/greet");
        assertThat(served.body()).contains("\"message\":\"Hello World!\"");
    }

    @Test
    void the_inject_quickstart_compiles_with_the_contributed_processors_and_runs(@TempDir Path tmp) throws Exception {
        Path project = fixture(tmp, "quickstart-inject");
        Path cache = TestCaches.dir("helidon-quickstart-cache");
        lock(project, cache);

        BuildPlan plan = plan(project, cache);
        BuildPlanResult built = plan.run();
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();
        Path classes = project.resolve("target/classes/io/helidon/examples/quickstart/inject");
        assertThat(classes.resolve("ApplicationBinding.class"))
                .as("@Service.GenerateBinding: the binding Main starts the registry with")
                .isRegularFile();
        assertThat(classes.resolve("GreetService__ServiceDescriptor.class")).isRegularFile();
        assertThat(
                        project.resolve(
                                "target/test-classes/io/helidon/examples/quickstart/inject/TestConfigFactory__ServiceDescriptor.class"))
                .as("compile-test runs the processors too")
                .isRegularFile();
        TestSummary tests = plan.get(BuildPlanner.TEST_RESULT).orElseThrow();
        assertThat(tests.failed()).isZero();
        assertThat(tests.succeeded()).isPositive();

        Served served = serve(project, cache, project.resolve("target"), "quickstart-inject.jar", "/greet");
        assertThat(served.body()).contains("\"message\":\"Hello World!\"");
        assertThat(served.log())
                .as("no binding step yet: the empty binding falls back to discovery")
                .contains("GenerateBinding");
    }

    private static Path fixture(Path tmp, String name) throws Exception {
        SchemaPresetExamplesTest.workerJarFromWorkspace(PluginJar.HELIDON, "plugins/helidon");
        Path project = tmp.resolve(name);
        PathUtil.copyTree(ROOT.resolve("plugins/helidon/src/test/resources").resolve(name), project);
        return project;
    }

    private static void lock(Path project, Path cache) throws Exception {
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).as("lock").isEmpty();
        assertThat(lock.success()).as("lock").isTrue();
    }

    private static BuildPlan plan(Path project, Path cache) throws Exception {
        return BuildPlanner.fullPlan(new BuildPlanner.Inputs(
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
    }

    /** What the application answered on {@code path}, and what it logged up to then. */
    private record Served(String body, String log) {}

    /**
     * {@code java -jar <jar>} from {@code dir} on the JDK {@code jk run} picks for the module, on a
     * free port; the response to {@code path} once the server says where it listens.
     */
    private static Served serve(Path project, Path cache, Path dir, String jar, String path) throws Exception {
        ExecPlan run = ExecPlans.execPlan(project, cache, "run", null, null);
        assertThat(run.error()).isNull();
        String java = run.argv().getFirst();
        Process p = new ProcessBuilder(java, "-Dserver.port=0", "-jar", jar)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuilder log = new StringBuilder();
        try {
            CompletableFuture<Integer> port = CompletableFuture.supplyAsync(() -> {
                try (BufferedReader out =
                        new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    Integer found = null;
                    while ((line = out.readLine()) != null) {
                        synchronized (log) {
                            log.append(line).append('\n');
                        }
                        Matcher m = PORT.matcher(line);
                        if (found == null && m.find() && !m.group(1).equals("0")) {
                            found = Integer.valueOf(m.group(1));
                            break;
                        }
                    }
                    return found;
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
            synchronized (log) {
                return new Served(response.body(), log.toString());
            }
        } finally {
            p.destroy();
            if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
        }
    }
}
