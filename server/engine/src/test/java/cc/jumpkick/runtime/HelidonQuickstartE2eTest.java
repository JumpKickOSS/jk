// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.workspace.BuildService;
import cc.jumpkick.runtime.workspace.ExecPlans;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.protocol.ExecPlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
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
 * <p>The inject quickstart is also the contract test of the {@code helidon-binding} step against
 * the Helidon release jk supports (27.0.1): {@code helidon-service-maven-plugin}'s generators,
 * driven without Maven, write the real binding (the app starts without the empty-binding
 * warning), {@code generate-main} writes an {@code ApplicationMain} that serves, the validator's
 * findings fail the build in Helidon's words, {@code test-binding} writes {@code
 * TestApplicationBinding} for the suites, a recompile keeps the empty binding, and the next build
 * restores the real one.
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

        assertThat(binding(project, "classes", "ApplicationBinding"))
                .as("helidon-binding compiled the real binding over the empty one")
                .doesNotContain(EMPTY_BINDING);

        Served served = serve(project, cache, project.resolve("target"), "quickstart-inject.jar", "/greet");
        assertThat(served.body()).contains("\"message\":\"Hello World!\"");
        assertThat(served.log())
                .as("the real binding: no fallback to discovery")
                .doesNotContain("GenerateBinding");

        // jk dev's rebuild on change is the compile goal: it keeps the empty binding (discovery)
        // instead of forking Helidon's generator on every edit.
        Path greet = project.resolve("src/main/java/io/helidon/examples/quickstart/inject/GreetService.java");
        Files.writeString(greet, Files.readString(greet) + "\n// edited\n");
        BuildPlanResult recompiled = plan(project, cache, true).run();
        assertThat(recompiled.errors()).isEmpty();
        assertThat(binding(project, "classes", "ApplicationBinding")).contains(EMPTY_BINDING);
    }

    /**
     * The compile goal {@code jk dev} runs on every change rewrites the step's output, which the
     * dev loop, {@code jk run} and the jar read. Helidon's processors read the module's resources
     * from the class output, so a resource edit recompiles as a source edit does, and either
     * leaves the empty binding. A build whose inputs all match
     * its memo still finds that empty binding is not its own output and restores the real one, or
     * the next {@code jk dev} or {@code jk run} starts on it with Helidon's warning.
     */
    @Test
    void the_compile_goal_leaves_the_empty_binding_on_an_edit_and_the_next_build_restores_it(@TempDir Path tmp)
            throws Exception {
        Path project = fixture(tmp, "quickstart-inject");
        Path cache = TestCaches.dir("helidon-quickstart-cache");
        lock(project, cache);
        assertThat(workspaceBuild(project, cache).success()).isTrue();
        assertThat(binding(project, "classes", "ApplicationBinding")).doesNotContain(EMPTY_BINDING);

        Path yaml = project.resolve("src/main/resources/application.yaml");
        String config = Files.readString(yaml);
        Files.writeString(yaml, config + "\n# edited\n");
        assertThat(plan(project, cache, true).run().errors()).isEmpty();
        assertThat(binding(project, "classes", "ApplicationBinding"))
                .as("Helidon's processors read the resources, so a resource edit recompiles like a source edit")
                .contains(EMPTY_BINDING);
        Files.writeString(yaml, config);
        assertThat(plan(project, cache, true).run().errors()).isEmpty();

        Path greet = project.resolve("src/main/java/io/helidon/examples/quickstart/inject/GreetService.java");
        String source = Files.readString(greet);
        Files.writeString(greet, source + "\n// edited\n");
        assertThat(plan(project, cache, true).run().errors()).isEmpty();
        Files.writeString(greet, source);
        assertThat(plan(project, cache, true).run().errors()).isEmpty();
        assertThat(binding(project, "classes", "ApplicationBinding"))
                .as("the recompile of a source edit (and of its revert) leaves the empty binding")
                .contains(EMPTY_BINDING);

        assertThat(workspaceBuild(project, cache).success()).isTrue();
        assertThat(binding(project, "classes", "ApplicationBinding"))
                .as("inputs as the first build had them: the build restores its own binding")
                .doesNotContain(EMPTY_BINDING);
    }

    @Test
    void generate_main_writes_an_application_main_that_serves(@TempDir Path tmp) throws Exception {
        Path project = fixture(tmp, "quickstart-inject");
        edit(
                project.resolve("jk.toml"),
                "main = \"io.helidon.examples.quickstart.inject.Main\"",
                "main = \"io.helidon.examples.quickstart.inject.ApplicationMain\"");
        edit(project.resolve("jk.toml"), "version = \"27.0.1\"\n", "version = \"27.0.1\"\ngenerate-main = true\n");
        Path cache = TestCaches.dir("helidon-quickstart-cache");
        lock(project, cache);

        BuildPlanResult built = plan(project, cache).run();
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();
        assertThat(binding(project, "classes", "ApplicationMain")).isNotEmpty();

        Served served = serve(project, cache, project.resolve("target"), "quickstart-inject.jar", "/greet");
        assertThat(served.body()).contains("\"message\":\"Hello World!\"");
        assertThat(served.log()).doesNotContain("GenerateBinding");
    }

    @Test
    void a_validator_finding_warns_as_maven_does_and_fails_the_build_with_fail_on_warning(@TempDir Path tmp)
            throws Exception {
        Path project = fixture(tmp, "quickstart-inject");
        Path pkg = project.resolve("src/main/java/io/helidon/examples/quickstart/inject");
        Files.writeString(pkg.resolve("RequestCounter.java"), """
                package io.helidon.examples.quickstart.inject;

                import io.helidon.service.registry.Service;

                @Service.PerRequest
                class RequestCounter {}
                """);
        Files.writeString(pkg.resolve("CounterHolder.java"), """
                package io.helidon.examples.quickstart.inject;

                import io.helidon.service.registry.Service;

                @Service.Singleton
                class CounterHolder {
                    @Service.Inject
                    CounterHolder(RequestCounter counter) {}
                }
                """);
        Path cache = TestCaches.dir("helidon-quickstart-cache");
        lock(project, cache);

        BuildPlanResult warned = plan(project, cache).run();
        assertThat(warned.errors()).isEmpty();
        assertThat(warned.success())
                .as("Maven's default, failOnWarning=false, lets a finding pass")
                .isTrue();
        assertThat(warned.warnings()).anySatisfy(warning -> assertThat(warning.message())
                .contains("Injection of request scoped service into a singleton without a supplier"));

        edit(project.resolve("jk.toml"), "version = \"27.0.1\"\n", "version = \"27.0.1\"\nfail-on-warning = true\n");
        BuildPlanResult built = plan(project, cache).run();
        assertThat(built.success()).isFalse();
        assertThat(built.errors()).anySatisfy(error -> assertThat(error.message())
                .contains("Application validation failed")
                .contains("Injection of request scoped service into a singleton without a supplier")
                .contains("io.helidon.examples.quickstart.inject.CounterHolder"));
    }

    @Test
    void test_binding_writes_the_test_application_binding_for_the_suites(@TempDir Path tmp) throws Exception {
        Path project = fixture(tmp, "quickstart-inject");
        edit(project.resolve("jk.toml"), "version = \"27.0.1\"\n", "version = \"27.0.1\"\ntest-binding = true\n");
        Path cache = TestCaches.dir("helidon-quickstart-cache");
        lock(project, cache);

        BuildPlan plan = plan(project, cache);
        BuildPlanResult built = plan.run();
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();
        assertThat(binding(project, "test-classes", "TestApplicationBinding"))
                .as("helidon-test-binding wrote TestApplicationBinding beside the test classes")
                .isNotEmpty();
        TestSummary tests = plan.get(BuildPlanner.TEST_RESULT).orElseThrow();
        assertThat(tests.failed()).isZero();
        assertThat(tests.succeeded()).isPositive();
    }

    /** What an empty binding extends; the real one does not mention it. */
    private static final String EMPTY_BINDING = "io/helidon/service/registry/EmptyBinding";

    /**
     * The class file a binding step wrote under its output {@code dir} ({@code classes} for
     * helidon-binding, {@code test-classes} for helidon-test-binding), as text.
     */
    private static String binding(Path project, String dir, String simpleName) throws IOException {
        String step = dir.equals("classes") ? "helidon-binding" : "helidon-test-binding";
        Path file = project.resolve("target/plugin/" + step + "/" + dir + "/io/helidon/examples/quickstart/inject/"
                + simpleName + ".class");
        assertThat(file).isRegularFile();
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    }

    private static void edit(Path file, String from, String to) throws IOException {
        String text = Files.readString(file);
        assertThat(text).contains(from);
        Files.writeString(file, text.replace(from, to));
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
                        project, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).as("lock").isEmpty();
        assertThat(lock.success()).as("lock").isTrue();
    }

    private static BuildPlan plan(Path project, Path cache) throws Exception {
        return plan(project, cache, false);
    }

    /** The full build, or with {@code compileOnly} the compile goal {@code jk dev} rebuilds with. */
    private static BuildPlan plan(Path project, Path cache, boolean compileOnly) throws Exception {
        return BuildPlanner.fullPlan(
                TestInputs.of(project, cache).withEstimatedTestCount(1).withCompileOnly(compileOnly));
    }

    /**
     * {@code jk build -S} as the engine runs it: the workspace build with its preflight memo, which
     * leaves a module whose inputs match alone.
     */
    private static WorkspaceResult workspaceBuild(Path project, Path cache) {
        WorkspaceRequest request =
                new WorkspaceRequest(project, cache, null, 1, null, true, false, 1, null, false, true);
        return BuildService.buildWorkspace(request, WorkspaceBuildListener.NOOP);
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
