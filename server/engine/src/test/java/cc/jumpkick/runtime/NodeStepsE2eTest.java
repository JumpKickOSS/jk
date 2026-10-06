// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compat.NodeProvisioning;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.node.DiscoveredNode;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [[node.steps]]} on the host's own Node.js with a project that needs nothing from a
 * registry: a codegen step keyed on a file outside the module, a lint check in the test tier, an
 * npx binary the lockfile holds through a {@code file:} dependency, and one it does not.
 */
@Tag("integration")
class NodeStepsE2eTest {

    @TempDir
    Path tmp;

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().withoutChecksums();

    private Path web;
    private int major;
    private Supplier<NodeProvisioning> production;

    @BeforeEach
    void setUp() throws IOException {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        DiscoveredNode host = found.get(0);
        major = Integer.parseInt(host.version().substring(0, host.version().indexOf('.')));
        web = Files.createDirectories(tmp.resolve("web"));
        Files.createDirectories(tmp.resolve("api"));
        Files.writeString(tmp.resolve("api/spec.json"), "{\"v\":1}");
        manifest("""
                [[node.steps]]
                name    = "gen"
                exec    = "node gen.js"
                inputs  = ["../api/spec.json", "gen.js"]
                outputs = ["src/gen.js"]

                [[node.steps]]
                name = "lint"
                run  = "lint"
                tier = "test"

                [[node.steps]]
                name    = "hello"
                npx     = "hello"
                outputs = ["hello.txt"]
                before  = "package"
                """);
        write("package.json", """
                {"name":"web","version":"1.0.0",
                 "scripts":{"build":"node build.js","lint":"node lint.js"},
                 "devDependencies":{"hello":"file:tools/hello"}}
                """);
        write("package-lock.json", """
                {"name":"web","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{
                  "":{"name":"web","version":"1.0.0","devDependencies":{"hello":"file:tools/hello"}},
                  "node_modules/hello":{"resolved":"tools/hello","link":true},
                  "tools/hello":{"name":"hello","version":"1.0.0","dev":true,"bin":{"hello":"bin.js"}}}}
                """);
        write("tools/hello/package.json", """
                {"name":"hello","version":"1.0.0","bin":{"hello":"bin.js"}}
                """);
        write("tools/hello/bin.js", """
                #!/usr/bin/env node
                require('fs').writeFileSync('hello.txt', 'hello');
                """);
        tmp.resolve("web/tools/hello/bin.js").toFile().setExecutable(true);
        write("gen.js", """
                const fs = require('fs'), path = require('path');
                fs.appendFileSync(path.join(__dirname, '..', 'gens.log'), 'g\\n');
                const spec = fs.readFileSync(path.join(__dirname, '..', 'api', 'spec.json'), 'utf8');
                fs.writeFileSync(path.join(__dirname, 'src', 'gen.js'), 'module.exports = ' + spec + ';\\n');
                """);
        write("build.js", """
                const fs = require('fs'), path = require('path');
                fs.appendFileSync(path.join(__dirname, '..', 'builds.log'), 'b\\n');
                fs.mkdirSync(path.join(__dirname, 'dist'), {recursive: true});
                fs.copyFileSync(path.join(__dirname, 'src', 'gen.js'), path.join(__dirname, 'dist', 'gen.js'));
                """);
        write("lint.js", """
                const fs = require('fs'), path = require('path');
                fs.appendFileSync(path.join(__dirname, '..', 'lints.log'), 'l\\n');
                if (fs.existsSync(path.join(__dirname, 'src', 'bad'))) { console.error('lint: bad'); process.exit(1); }
                """);
        write("src/main.js", "module.exports = 1;\n");
        LockfileWriter.write(
                Lockfile.empty(JkVersion.VERSION).withNode(new NodePin(host.version(), null, null, Map.of())),
                web.resolve("jk-lock.toml"));
        production = PlannerNodeSetup.provisioning;
        URI nowhere = http.base();
        PlannerNodeSetup.provisioning = () -> new NodeProvisioning(
                new ToolRegistry(tmp.resolve("tools")),
                new Http(),
                new NodeDiscovery(),
                nowhere,
                new PackageManagerResolver(new Http(), nowhere),
                NodePlatform.host());
    }

    @AfterEach
    void tearDown() {
        if (production != null) PlannerNodeSetup.provisioning = production;
    }

    @Test
    void steps_run_in_their_place_and_rerun_only_for_what_they_read() throws Exception {
        Session session = session();
        BuildPlanResult first = run(plan(session, false), session);
        assertThat(first.errors()).isEmpty();
        assertThat(web.resolve("dist/gen.js")).content().contains("\"v\":1");
        assertThat(web.resolve("hello.txt"))
                .as("the npx binary from the file: dependency")
                .hasContent("hello");
        assertThat(lines("gens.log")).isEqualTo(1);
        assertThat(lines("lints.log")).isEqualTo(1);

        assertThat(run(plan(session, false), session).errors()).isEmpty();
        assertThat(lines("gens.log")).as("codegen cached").isEqualTo(1);
        assertThat(lines("builds.log")).as("build cached").isEqualTo(1);
        assertThat(lines("lints.log")).as("lint cached as passed").isEqualTo(1);

        Files.writeString(tmp.resolve("api/spec.json"), "{\"v\":2}");
        assertThat(run(plan(session, false), session).errors()).isEmpty();
        assertThat(lines("gens.log"))
                .as("an input outside the module reruns codegen")
                .isEqualTo(2);
        assertThat(lines("builds.log"))
                .as("and the build that reads its output")
                .isEqualTo(2);
        assertThat(web.resolve("dist/gen.js")).content().contains("\"v\":2");

        write("src/main.js", "module.exports = 2;\n");
        assertThat(run(plan(session, false), session).errors()).isEmpty();
        assertThat(lines("gens.log")).as("a source edit does not rerun codegen").isEqualTo(2);
        assertThat(lines("builds.log")).as("but reruns the build").isEqualTo(3);

        Files.delete(web.resolve("src/gen.js"));
        assertThat(run(plan(session, false), session).errors()).isEmpty();
        assertThat(web.resolve("src/gen.js")).as("restored from the cache").exists();
        assertThat(web.resolve("src/main.js"))
                .as("and nothing beside it touched")
                .exists();
        assertThat(lines("gens.log")).isEqualTo(2);

        ActionCache cache =
                new ActionCache(JkStores.cacheCas(tmp.resolve("cache")), CacheTree.ACTIONS.under(tmp.resolve("cache")));
        List<TaskForecast.Task> forecast = PlannerNode.forecast(
                JkBuildParser.parse(web.resolve("jk.toml")),
                web,
                web,
                LockfileReader.read(web.resolve("jk-lock.toml")),
                cache,
                false,
                false);
        assertThat(forecast)
                .filteredOn(t -> t.name().equals(TaskNames.NODE_STEP_PREFIX + "gen"))
                .singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo(TaskForecast.Status.CACHED));
        Files.writeString(tmp.resolve("api/spec.json"), "{\"v\":3}");
        List<TaskForecast.Task> stale = PlannerNode.forecast(
                JkBuildParser.parse(web.resolve("jk.toml")),
                web,
                web,
                LockfileReader.read(web.resolve("jk-lock.toml")),
                cache,
                false,
                false);
        assertThat(stale)
                .filteredOn(t -> t.name().equals(TaskNames.NODE_STEP_PREFIX + "gen"))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.status()).isEqualTo(TaskForecast.Status.RUN);
                    assertThat(t.text()).contains("../api/spec.json");
                });
    }

    @Test
    void a_test_tier_step_fails_the_build_and_is_skipped_with_the_tests() throws Exception {
        write("src/bad", "x");
        Session session = session();
        BuildPlanResult failed = run(plan(session, false), session);
        assertThat(failed.errors()).isNotEmpty();
        assertThat(String.join(
                        "\n",
                        failed.errors().stream()
                                .map(BuildPlanResult.Diagnostic::message)
                                .toList()))
                .contains("lint");

        BuildPlanResult skipped = run(plan(session, true), session);
        assertThat(skipped.errors()).as("--skip-tests leaves the lint step out").isEmpty();
        assertThat(lines("lints.log")).isEqualTo(1);
    }

    @Test
    void steps_wait_on_the_install_and_hold_back_only_what_they_name() throws Exception {
        BuildPlan plan = plan(session(), false);
        Task gen = task(plan, TaskNames.NODE_STEP_PREFIX + "gen");
        Task lint = task(plan, TaskNames.NODE_STEP_PREFIX + "lint");
        Task hello = task(plan, TaskNames.NODE_STEP_PREFIX + "hello");
        Task build = task(plan, TaskNames.NODE_BUILD);
        assertThat(gen.requires()).containsExactly(TaskNames.NODE_INSTALL);
        assertThat(build.requires()).containsExactly(gen.name());
        assertThat(lint.requires()).as("declared after gen, beside the build").containsExactly(gen.name());
        assertThat(hello.requires()).containsExactly(TaskNames.NODE_BUILD);
    }

    @Test
    void an_angular_project_with_no_build_script_builds_with_ng_from_its_install() throws Exception {
        manifest("");
        write("angular.json", "{\"projects\":{\"shop\":{}}}");
        write("package.json", """
                {"name":"web","version":"1.0.0","devDependencies":{"ng-stub":"file:tools/ng"}}
                """);
        write("package-lock.json", """
                {"name":"web","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{
                  "":{"name":"web","version":"1.0.0","devDependencies":{"ng-stub":"file:tools/ng"}},
                  "node_modules/ng-stub":{"resolved":"tools/ng","link":true},
                  "tools/ng":{"name":"ng-stub","version":"1.0.0","dev":true,"bin":{"ng":"ng.js"}}}}
                """);
        write("tools/ng/package.json", """
                {"name":"ng-stub","version":"1.0.0","bin":{"ng":"ng.js"}}
                """);
        write("tools/ng/ng.js", """
                #!/usr/bin/env node
                const fs = require('fs');
                if (process.argv[2] !== 'build') process.exit(3);
                fs.mkdirSync('dist/shop/browser', {recursive: true});
                fs.writeFileSync('dist/shop/browser/index.html', '<app-root/>');
                """);
        web.resolve("tools/ng/ng.js").toFile().setExecutable(true);
        Session session = session();
        assertThat(run(plan(session, false), session).errors()).isEmpty();
        assertThat(web.resolve("dist/shop/browser/index.html")).hasContent("<app-root/>");
    }

    @Test
    void exports_name_what_the_build_writes_and_nothing_else() throws Exception {
        manifest("""
                [node.exports]
                bundle = "dist"
                generated = "src/gen.js"

                [[node.steps]]
                name    = "gen"
                exec    = "node gen.js"
                inputs  = ["../api/spec.json", "gen.js"]
                outputs = ["src/gen.js"]
                """);
        Session session = session();
        BuildPlan plan = plan(session, false);
        assertThat(run(plan, session).errors()).isEmpty();
        assertThat(plan.get(BuildPlanner.NODE_EXPORTS).orElseThrow())
                .containsEntry("bundle", web.resolve("dist"))
                .containsEntry("generated", web.resolve("src/gen.js"));

        manifest("""
                [node.exports]
                stray = "src/main.js"
                """);
        BuildPlanResult refused = run(plan(session, false), session);
        assertThat(String.join(
                        "\n",
                        refused.errors().stream()
                                .map(BuildPlanResult.Diagnostic::message)
                                .toList()))
                .contains("[node] exports.stray = \"src/main.js\" is not out/ or a step's output");
    }

    @Test
    void an_npx_of_a_package_the_lockfile_does_not_hold_is_refused() throws Exception {
        manifest("""
                [[node.steps]]
                name = "fetch"
                npx  = "not-in-the-lock --version"
                """);
        Session session = session();
        BuildPlanResult r = run(plan(session, false), session);
        assertThat(r.errors()).isNotEmpty();
        assertThat(String.join(
                        "\n",
                        r.errors().stream()
                                .map(BuildPlanResult.Diagnostic::message)
                                .toList()))
                .contains("npx not-in-the-lock is not in the lockfile")
                .contains("allow-unlocked = true");
    }

    private Session session() {
        return Session.defaults().withCacheDir(tmp.resolve("cache")).withVariant("", Map.of());
    }

    private void manifest(String steps) throws IOException {
        write(
                "jk.toml",
                "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\n\n[node]\nversion = " + major + "\n\n" + steps);
    }

    private static Task task(BuildPlan plan, String name) {
        return plan.steps().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private BuildPlan plan(Session session, boolean skipTests) {
        return BuildPlanner.fullPlan(new BuildPlanner.Inputs(
                web,
                tmp.resolve("cache"),
                web.resolve("jk.toml"),
                web.resolve("jk-lock.toml"),
                web,
                1,
                1,
                null,
                null,
                skipTests,
                false,
                false,
                false,
                Set.of(),
                session));
    }

    private static BuildPlanResult run(BuildPlan plan, Session session) throws Exception {
        BuildPlanResult[] out = new BuildPlanResult[1];
        SessionContext.runWhere(session, () -> out[0] = plan.run());
        return out[0];
    }

    private long lines(String name) throws IOException {
        Path log = tmp.resolve(name);
        return Files.exists(log) ? Files.readAllLines(log).size() : 0;
    }

    private void write(String rel, String body) throws IOException {
        Path file = web.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }
}
