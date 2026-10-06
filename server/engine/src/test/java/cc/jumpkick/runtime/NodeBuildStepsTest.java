// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.compat.NodeProvisioning;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.TestFailureMode;
import cc.jumpkick.node.DiscoveredNode;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.testing.LoopbackHttp;
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
 * A node module's install, build and test steps on the host's own Node.js, with a project that
 * needs nothing from a registry: what each step keys on, what a second build skips, and what a
 * failure says.
 */
@Tag("integration")
class NodeBuildStepsTest {

    @TempDir
    Path tmp;

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().withoutChecksums();

    private Path web;
    private Supplier<NodeProvisioning> production;

    @BeforeEach
    void setUp() throws IOException {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        DiscoveredNode host = found.get(0);
        web = Files.createDirectories(tmp.resolve("web"));
        write("jk.toml", "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = " + major(host.version()) + "\n");
        write("package.json", """
                {"name":"web","version":"1.0.0",
                 "scripts":{"build":"node build.js","test":"node --test"}}
                """);
        write("package-lock.json", """
                {"name":"web","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"web","version":"1.0.0"}}}
                """);
        write("build.js", """
                const fs = require('fs'), path = require('path');
                fs.appendFileSync(path.join(__dirname, '..', 'builds.log'), 'b\\n');
                fs.mkdirSync(path.join(__dirname, 'dist'), {recursive: true});
                fs.copyFileSync(path.join(__dirname, 'src', 'main.js'), path.join(__dirname, 'dist', 'main.js'));
                fs.writeFileSync(path.join(__dirname, 'dist', 'env.txt'), String(process.env.VITE_API_URL));
                if (fs.existsSync(path.join(__dirname, 'src', 'broken'))) {
                  console.error('src/main.js(1,7): error TS2322: Type string is not assignable');
                  process.exit(2);
                }
                """);
        write("src/main.js", "module.exports = 1;\n");
        write("test/main.test.js", """
                const test = require('node:test'), assert = require('node:assert');
                const fs = require('fs'), path = require('path');
                fs.appendFileSync(path.join(__dirname, '..', '..', 'tests.log'), 't\\n');
                test('adds', () => assert.strictEqual(1 + 1, 2));
                test('reads', () => assert.ok(!fs.existsSync(path.join(__dirname, 'fail'))));
                """);
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
    void a_second_build_runs_nothing_and_each_input_reruns_what_reads_it() throws Exception {
        Session session = session(Map.of("VITE_API_URL", "a", "HOME", "/one"));
        BuildPlan first = plan(session);
        BuildPlanResult r = run(first, session);
        assertThat(r.errors()).isEmpty();
        assertThat(web.resolve("dist/main.js")).exists();
        assertThat(web.resolve("dist/env.txt")).hasContent("a");
        assertThat(lines("builds.log")).isEqualTo(1);
        assertThat(lines("tests.log")).isEqualTo(1);
        TestSummary tests = first.get(BuildPlanner.TEST_RESULT).orElseThrow();
        assertThat(tests.total()).as("node --test's JUnit report").isEqualTo(2);
        assertThat(tests.succeeded()).isEqualTo(2);

        Path marker = Files.writeString(web.resolve("node_modules/.kept"), "x");
        BuildPlan again = plan(session);
        assertThat(run(again, session).errors()).isEmpty();
        assertThat(lines("builds.log")).as("build cached").isEqualTo(1);
        assertThat(lines("tests.log")).as("tests replayed").isEqualTo(1);
        assertThat(again.get(BuildPlanner.TEST_RESULT).orElseThrow().total()).isEqualTo(2);
        assertThat(marker).as("install not rerun").exists();

        write("src/main.js", "module.exports = 2;\n");
        assertThat(run(plan(session), session).errors()).isEmpty();
        assertThat(lines("builds.log")).as("a source edit rebuilds").isEqualTo(2);
        assertThat(marker).as("and does not reinstall").exists();

        Files.writeString(web.resolve("package-lock.json"), Files.readString(web.resolve("package-lock.json")) + "\n");
        assertThat(run(plan(session), session).errors()).isEmpty();
        assertThat(marker).as("a lockfile edit reinstalls").doesNotExist();
        assertThat(lines("builds.log")).as("and rebuilds").isEqualTo(3);

        deleteTree(web.resolve("node_modules"));
        assertThat(run(plan(session), session).errors()).isEmpty();
        assertThat(web.resolve("node_modules"))
                .as("a missing node_modules reinstalls")
                .isDirectory();
        assertThat(lines("builds.log")).as("without rebuilding").isEqualTo(3);

        Session home = session(Map.of("VITE_API_URL", "a", "HOME", "/two"));
        assertThat(run(plan(home), home).errors()).isEmpty();
        assertThat(lines("builds.log")).as("HOME is not an input").isEqualTo(3);

        Session vite = session(Map.of("VITE_API_URL", "b", "HOME", "/one"));
        assertThat(run(plan(vite), vite).errors()).isEmpty();
        assertThat(lines("builds.log")).as("a framework variable is").isEqualTo(4);
        assertThat(web.resolve("dist/env.txt")).hasContent("b");

        deleteTree(web.resolve("dist"));
        assertThat(run(plan(session), session).errors()).isEmpty();
        assertThat(web.resolve("dist/main.js")).as("restored from the cache").exists();
        assertThat(lines("builds.log")).isEqualTo(4);
    }

    @Test
    void skip_node_runs_no_node_step_and_keeps_the_output_there_is() throws Exception {
        Session session = session(Map.of());
        assertThat(run(plan(session), session).errors()).isEmpty();
        Session skipping = session.withSkipNode(true);
        write("src/main.js", "module.exports = 3;\n");
        BuildPlan skipped = plan(skipping);
        assertThat(run(skipped, skipping).errors()).isEmpty();
        assertThat(lines("builds.log")).isEqualTo(1);
        assertThat(lines("tests.log")).isEqualTo(1);
        assertThat(skipped.get(BuildPlanner.NODE_OUT)).contains(web.resolve("dist"));
    }

    @Test
    void a_failing_test_fails_the_build_unless_failures_are_reported() throws Exception {
        write("test/fail", "");
        Session session = session(Map.of());
        BuildPlanResult failed = run(plan(session), session);
        assertThat(failed.success()).isFalse();
        assertThat(failed.errors()).anyMatch(d -> "test-failure".equals(d.code()) && "reads".equals(d.method()));

        Session reporting = session.withTestFailures(TestFailureMode.REPORT);
        BuildPlan reported = plan(reporting);
        BuildPlanResult ok = run(reported, reporting);
        assertThat(ok.success()).isTrue();
        assertThat(ok.warnings()).anyMatch(d -> TestLaunch.FAILURES_REPORTED.equals(d.code()));
        assertThat(reported.get(BuildPlanner.TEST_RESULT).orElseThrow().failed())
                .isEqualTo(1);
    }

    @Test
    void skip_tests_leaves_the_test_step_out() throws Exception {
        Session session = session(Map.of());
        BuildPlan plan = plan(session, true);
        assertThat(run(plan, session).errors()).isEmpty();
        assertThat(lines("builds.log")).isEqualTo(1);
        assertThat(lines("tests.log")).isZero();
    }

    @Test
    void a_failing_build_names_its_command_and_its_diagnostics() throws Exception {
        write("src/broken", "");
        Session session = session(Map.of());
        BuildPlanResult r = run(plan(session), session);
        assertThat(r.success()).isFalse();
        assertThat(r.errors())
                .anyMatch(d -> d.message() != null && d.message().startsWith("src/main.js:1:7: TS2322: Type string"))
                .anyMatch(d -> d.message() != null && d.message().contains("`run build` exited 2"));
    }

    @Test
    void a_missing_lockfile_or_script_says_how_to_fix_it() throws Exception {
        write(
                "jk.toml",
                Files.readString(web.resolve("jk.toml")).replace("node = ", "[node]\nbuild = \"bundle\"\nversion = "));
        Path lockfile = web.resolve("package-lock.json");
        String lock = Files.readString(lockfile);
        Files.delete(lockfile);
        Session session = session(Map.of());
        BuildPlanResult noLock = run(plan(session), session);
        assertThat(noLock.errors())
                .anyMatch(d -> d.message() != null
                        && d.message().contains("no package-lock.json — run npm install once and commit it"));

        Files.writeString(lockfile, lock);
        BuildPlanResult noScript = run(plan(session), session);
        assertThat(noScript.errors())
                .anyMatch(d -> d.message() != null && d.message().contains("package.json has no `bundle` script"));
    }

    private Session session(Map<String, String> clientEnv) {
        return Session.defaults().withCacheDir(tmp.resolve("cache")).withVariant("", clientEnv);
    }

    private BuildPlan plan(Session session) {
        return plan(session, false);
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

    private static void deleteTree(Path dir) throws IOException {
        PathUtil.deleteRecursivelyOrThrow(dir);
    }

    private static int major(String version) {
        return Integer.parseInt(version.substring(0, version.indexOf('.')));
    }
}
