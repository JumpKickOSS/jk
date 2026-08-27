// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.task.ActionKey;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The engine-hosted request round-trips the slim-client migration moved onto the socket: lock,
 * audit, rebuild, compile, tool-resolve and the two cache sweeps. A real-socket contract test —
 * the fixture lives in {@link EngineServerHarness}.
 */
@Tag("integration")
class EngineServerRequestTest extends EngineServerHarness {

    /**
     * Engine-hosted {@code jk lock} round-trip (Wave 1 of the slim-client migration): a real server
     * over the socket, a tiny fixture project, and a mock Maven repo standing in for every remote
     * (the request's {@code repoUrl} override). Asserts the full wire conversation — {@code
     * lock-module} → plan burst → {@code lock-package} stream → count-carrying {@code plan-finish}
     * → {@code lock-finish} — and that the engine actually wrote {@code jk-lock.toml}.
     */
    @Test
    void lock_request_resolves_and_writes_the_lockfile_over_the_socket() throws Exception {
        String previousM2 = System.getProperty("jk.m2.local");
        System.setProperty("jk.m2.local", shortTempDir().toString()); // never touch the real ~/.m2
        HttpServer repo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            Map<String, byte[]> served = new HashMap<>();
            // jk injects the latest-stable JUnit Platform into every project's TEST scope, so the
            // mock repo must offer those coords (dependency-free stubs) alongside the project dep.
            seedArtifact(served, "org.junit.jupiter", "junit-jupiter", "6.1.0");
            seedArtifact(served, "org.junit.platform", "junit-platform-launcher", "6.1.0");
            seedArtifact(served, "com.foo", "leaf", "1.0");
            repo.createContext("/", exchange -> {
                byte[] body = served.get(exchange.getRequestURI().getPath());
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            repo.start();
            String repoUrl = "http://127.0.0.1:" + repo.getAddress().getPort();

            Path project = shortTempDir();
            Files.writeString(project.resolve("jk.toml"), """
                    group   = "com.example"
                    name    = "app"
                    version = "1.0.0"
                    jdk     = 25
                    java    = 25

                    [dependencies]
                    leaf = { group = "com.foo", name = "leaf", version = "1.0" }
                    """);
            Path cache = shortTempDir();

            EnginePaths.Paths p = paths(shortTempDir());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
            Thread serverThread = runInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            List<String> types = new ArrayList<>();
            String lockModule = null;
            String planFinish = null;
            String lockFinish = null;
            String buildError = null;
            boolean sawAnyPackage = false;
            int lastPackageTotal = -1;
            try (Client c = new Client(EnginePaths.activeSocket(p))) {
                c.sendLine(ProtoJobs.lockRequest(
                        project.toString(),
                        cache.toString(),
                        List.of(),
                        false,
                        false,
                        repoUrl,
                        false,
                        false,
                        false,
                        false));
                String line;
                while ((line = c.readLine()) != null) {
                    String type = EngineProtocol.typeOf(line);
                    types.add(type);
                    switch (type) {
                        case EngineProtocol.LOCK_MODULE -> lockModule = line;
                        case EngineProtocol.LOCK_PACKAGE -> {
                            // Coalesced stream (2f3522d): latest package + running total
                            // individual names are progress samples, not a per-package feed.
                            sawAnyPackage = true;
                            lastPackageTotal = Jsonl.intValue(line, "total", -1);
                        }
                        case EngineProtocol.BUILDPLAN_FINISH -> planFinish = line;
                        case EngineProtocol.LOCK_FINISH -> lockFinish = line;
                        // Terminal too (a pre-plan failure): break instead of waiting forever for a
                        // lock-finish that will never come — otherwise the server (reading this
                        // connection for a cancel/EOF) and this loop mutually wait, and the test
                        // hangs to timeout. The real client (EngineResolveAdapter) does the same.
                        case EngineProtocol.ERROR -> buildError = line;
                        default -> {
                            /* plan/progress events — presence asserted via `types` below */
                        }
                    }
                    if (lockFinish != null || buildError != null) break;
                }
            }

            assertThat(buildError)
                    .as("engine reported a pre-plan error instead of hosting the lock")
                    .isNull();
            assertThat(lockModule).isNotNull();
            assertThat(Jsonl.str(lockModule, "dir")).isEqualTo(project.toString());
            assertThat(Jsonl.str(lockModule, "coord")).isEqualTo("com.example:app");
            assertThat(types).contains(EngineProtocol.PLAN_TASK, EngineProtocol.PLAN_DONE);
            assertThat(sawAnyPackage)
                    .as("at least one coalesced lock-package event")
                    .isTrue();
            assertThat(lastPackageTotal)
                    .as("running total covers every locked package")
                    .isEqualTo(3); // leaf + 2 junit defaults

            assertThat(planFinish).isNotNull();
            assertThat(Jsonl.bool(planFinish, "success", false)).isTrue();
            assertThat(Jsonl.longValue(planFinish, "lockPackages", -1)).isEqualTo(3); // leaf + 2 junit defaults

            assertThat(lockFinish).isNotNull();
            assertThat(Jsonl.bool(lockFinish, "success", false)).isTrue();
            assertThat(Jsonl.intValue(lockFinish, "exitCode", -1)).isEqualTo(0);

            // The engine (not the client) wrote the lockfile.
            assertThat(Files.isRegularFile(project.resolve("jk-lock.toml"))).isTrue();
            var lock = LockfileReader.read(project.resolve("jk-lock.toml"));
            assertThat(lock.artifacts().stream().anyMatch(a -> a.matchesModule("com.foo:leaf")))
                    .as("lock contains com.foo:leaf")
                    .isTrue();

            server.close();
            serverThread.join(5_000);
        } finally {
            repo.stop(0);
            if (previousM2 != null) System.setProperty("jk.m2.local", previousM2);
            else System.clearProperty("jk.m2.local");
            LockfileReader.clearCache();
        }
    }

    /**
     * Engine-hosted {@code jk audit} round-trip (Wave 2 of the slim-client migration — the hosted
     * worker commands): a real server over the socket forks a real {@code jk-auditor} worker JVM
     * (located via {@code -Djk.auditor.plugin.jar}, wired by the Gradle build) against a mock OSV
     * API. Asserts the single-plan wire conversation — plan burst → plan events → structured
     * {@code audit-finding} stream → terminal {@code plan-finish} — carrying the mock vulnerability.
     */
    @Test
    void audit_request_forks_the_worker_and_streams_findings_over_the_socket() throws Exception {
        HttpServer osv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            osv.createContext("/querybatch", exchange -> {
                byte[] body = "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-test-1\"}]}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            osv.createContext("/vulns/", exchange -> {
                byte[] body = "{\"summary\":\"Stub vulnerability\",\"database_specific\":{\"severity\":\"HIGH\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            osv.start();
            String base = "http://127.0.0.1:" + osv.getAddress().getPort();

            Path project = shortTempDir();
            Files.writeString(project.resolve("jk-lock.toml"), """
                    version = 2
                    generated-by = "jk test"
                    resolution-algorithm = "pubgrub-v1"

                    [[artifact]]
                    name     = "com.foo:leaf"
                    version  = "1.0"
                    source   = "central+https://repo.maven.apache.org/maven2/"
                    checksum = "sha256:d65226949713c4c61a784f41c51167e7b0316f93764398ebba9e4336b3d954c2"
                    scopes   = ["main"]
                    """);
            Path cache = shortTempDir();

            EnginePaths.Paths p = paths(shortTempDir());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
            Thread serverThread = runInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            List<String> types = new ArrayList<>();
            String finding = null;
            String planFinish = null;
            String buildError = null;
            try (Client c = new Client(EnginePaths.activeSocket(p))) {
                c.sendLine(ProtoJobs.auditRequest(
                        project.toString(), cache.toString(), "LOW", base + "/querybatch", base + "/vulns/", false));
                String line;
                while ((line = c.readLine()) != null) {
                    String type = EngineProtocol.typeOf(line);
                    types.add(type);
                    switch (type) {
                        case EngineProtocol.AUDIT_FINDING -> finding = line;
                        case EngineProtocol.BUILDPLAN_FINISH -> planFinish = line;
                        // Terminal too (e.g. the worker jar wasn't locatable): break instead of
                        // waiting for a plan-finish that will never come — the real client
                        // (EnginePluginAdapter) treats build-error the same way.
                        case EngineProtocol.ERROR -> buildError = line;
                        default -> {
                            /* plan/progress events — presence asserted via `types` below */
                        }
                    }
                    if (planFinish != null || buildError != null) break;
                }
            }

            assertThat(buildError)
                    .as("engine reported a pre-plan error instead of hosting the audit")
                    .isNull();
            assertThat(types).contains(EngineProtocol.PLAN_TASK, EngineProtocol.PLAN_DONE);
            assertThat(finding)
                    .as("audit-finding event for the mock vulnerability")
                    .isNotNull();
            assertThat(Jsonl.str(finding, "module")).isEqualTo("com.foo:leaf");
            assertThat(Jsonl.str(finding, "version")).isEqualTo("1.0");
            assertThat(Jsonl.str(finding, "vulnId")).isEqualTo("GHSA-test-1");
            assertThat(Jsonl.str(finding, "summary")).isEqualTo("Stub vulnerability");

            assertThat(planFinish).isNotNull();
            assertThat(Jsonl.bool(planFinish, "success", false)).isTrue();

            server.close();
            serverThread.join(5_000);
        } finally {
            osv.stop(0);
            LockfileReader.clearCache();
        }
    }

    /**
     * Engine-hosted {@code jk compile} round-trip (Wave 3 of the slim-client migration — the
     * in-process {@code BuildPlanner} stragglers): a real server over the socket runs the shared
     * plan in compile-only mode against a tiny dependency-free fixture (a fresh empty {@code
     * jk-lock.toml}, so no network resolve). Asserts the single-plan wire conversation — plan burst →
     * plan events → terminal {@code plan-finish} — and that the engine actually compiled the class.
     */
    /**
     * The session envelope's {@code rebuild} flag must defeat every engine-side freshness fast
     * path: after a first build populates the stamps and action cache, a second request with
     * {@code withSession(..., rebuild=true)} must genuinely re-run the compile — never label it
     * "up to date". (Regression: --redo once vanished between the CLI and the stamp checks.)
     */
    @Test
    void rebuild_in_the_session_envelope_defeats_the_freshness_fast_path() throws Exception {
        Path project = shortTempDir();
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                """);
        Path src = project.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """);
        Files.writeString(project.resolve("jk-lock.toml"), """
                version = 2
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                """);
        Path cache = shortTempDir();

        EnginePaths.Paths p = paths(shortTempDir());
        // Real version so the freshened lock's jk-min floor can never exceed the test server's
        // version between request #1 and #2.
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, JkVersion.VERSION, null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
        try {
            String plain = ProtoJobs.singleBuildRequest(
                    project.toString(), cache.toString(), null, 1, null, true, false, false, false);

            // First build: real compile, stamps + caches populated.
            assertThat(runToBuildPlanFinish(p, plain)).doesNotContain("\"buildOutcome\":\"up-to-date\"");
            // Sanity: a plain second build IS the fast path.
            assertThat(runToBuildPlanFinish(p, plain)).contains("\"buildOutcome\":\"up-to-date\"");
            // The envelope's rebuild defeats it.
            String distrust = ProtoSession.withSession(plain, null, null, null, true);
            assertThat(runToBuildPlanFinish(p, distrust))
                    .as("rebuild must reach the engine's stamp checks")
                    .doesNotContain("\"buildOutcome\":\"up-to-date\"");
        } finally {
            server.close();
            serverThread.join(10_000);
        }
    }

    @Test
    void compile_request_compiles_the_project_over_the_socket() throws Exception {
        Path project = shortTempDir();
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                """);
        Path src = project.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """);
        // A fresh empty lock (newer than jk.toml) stands in for "already locked" — the plan's
        // parse-build then uses it verbatim instead of resolving over the network.
        Files.writeString(project.resolve("jk-lock.toml"), """
                version = 2
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                """);
        Path cache = shortTempDir();

        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        List<String> types = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        String planFinish = null;
        String buildError = null;
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            c.sendLine(ProtoJobs.compileRequest(project.toString(), cache.toString(), null, false, false, false));
            String line;
            while ((line = c.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                types.add(type);
                switch (type) {
                    case EngineProtocol.BUILDPLAN_FINISH -> planFinish = line;
                    case EngineProtocol.BUILDPLAN_DIAGNOSTIC -> diagnostics.add(line);
                    // Terminal too — break instead of waiting for a plan-finish that will never
                    // come (the mutual-wait shape the audit test also guards against).
                    case EngineProtocol.ERROR -> buildError = line;
                    default -> {
                        /* plan/progress events — presence asserted via `types` below */
                    }
                }
                if (planFinish != null || buildError != null) break;
            }
        }

        assertThat(buildError)
                .as("engine reported a pre-plan error instead of hosting the compile")
                .isNull();
        assertThat(types).contains(EngineProtocol.PLAN_TASK, EngineProtocol.PLAN_DONE);
        assertThat(planFinish).isNotNull();
        assertThat(Jsonl.bool(planFinish, "success", false))
                .as("hosted compile succeeded; diagnostics: " + diagnostics)
                .isTrue();
        // The engine (not the client) ran the compile.
        assertThat(Files.isRegularFile(project.resolve("target/classes/main/example/Hello.class")))
                .isTrue();

        server.close();
        serverThread.join(5_000);
        LockfileReader.clearCache();
    }

    /**
     * Engine-hosted {@code jk tool install}/{@code tool run} resolution round-trip (Wave 4 of the
     * slim-client migration): a real server over the socket resolves a Maven-published tool against
     * a mock repo (the {@code --main} override skips manifest reading — the served jar is a stub).
     * Asserts the single-plan wire conversation ends in a {@code plan-finish} carrying the resolved
     * main class + classpath, and that the engine actually fetched the jar into the CAS.
     */
    @Test
    void tool_resolve_request_resolves_and_fetches_over_the_socket() throws Exception {
        String previousM2 = System.getProperty("jk.m2.local");
        System.setProperty("jk.m2.local", shortTempDir().toString()); // never touch the real ~/.m2
        HttpServer repo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            Map<String, byte[]> served = new HashMap<>();
            seedArtifact(served, "com.example", "widget-cli", "1.0.0");
            repo.createContext("/", exchange -> {
                byte[] body = served.get(exchange.getRequestURI().getPath());
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            repo.start();
            String repoUrl = "http://127.0.0.1:" + repo.getAddress().getPort();
            Path cache = shortTempDir();

            EnginePaths.Paths p = paths(shortTempDir());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
            Thread serverThread = runInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            List<String> types = new ArrayList<>();
            String planFinish = null;
            String buildError = null;
            try (Client c = new Client(EnginePaths.activeSocket(p))) {
                c.sendLine(ProtoSession.toolResolveRequest(
                        "com.example:widget-cli:1.0.0",
                        List.of(),
                        "widget",
                        "com.example.Main",
                        repoUrl,
                        cache.toString()));
                String line;
                while ((line = c.readLine()) != null) {
                    String type = EngineProtocol.typeOf(line);
                    types.add(type);
                    switch (type) {
                        case EngineProtocol.BUILDPLAN_FINISH -> planFinish = line;
                        case EngineProtocol.ERROR -> buildError = line;
                        default -> {
                            /* plan/progress events — presence asserted via `types` below */
                        }
                    }
                    if (planFinish != null || buildError != null) break;
                }
            }

            assertThat(buildError)
                    .as("engine reported a pre-plan error instead of hosting the resolve")
                    .isNull();
            assertThat(types).contains(EngineProtocol.PLAN_TASK, EngineProtocol.PLAN_DONE);
            assertThat(planFinish).isNotNull();
            assertThat(Jsonl.bool(planFinish, "success", false)).isTrue();
            assertThat(Jsonl.str(planFinish, "toolMainClass")).isEqualTo("com.example.Main");
            List<String> classpath = Jsonl.strArray(planFinish, "toolClasspath");
            assertThat(classpath).hasSize(1);
            // The engine (not the client) fetched the jar — the classpath entry exists on disk.
            assertThat(Files.isRegularFile(Path.of(classpath.get(0)))).isTrue();

            server.close();
            serverThread.join(5_000);
        } finally {
            repo.stop(0);
            if (previousM2 != null) System.setProperty("jk.m2.local", previousM2);
            else System.clearProperty("jk.m2.local");
        }
    }

    /**
     * Engine-hosted {@code jk cache clean} round-trip: a real server over the socket sweeps a
     * fixture cache holding a leftover cache-CAS temp and a 90-day-old action key. Asserts the
     * single-plan wire conversation ends in a summary-carrying {@code plan-finish} counting the one
     * temp, and that the {@code.prune.lock} cross-process guard was created — the hosted path always
     * takes it, so no second process can prune the same root underneath this one.
     *
     * <p>The aged key is a <strong>survivor</strong>: entries are evicted only to bring the action
     * cache under its size budget, oldest first, and this fixture is orders of magnitude below it.
     * Age on its own never deletes.
     *
     * <p>Both planted files are <strong>cache</strong> tier — a plain prune owns the cache root
     * only; store temps belong to {@code jk storage clean}, which {@code CacheCommandTest} pins.
     */
    @Test
    void cache_clean_request_sweeps_temps_over_the_socket() throws Exception {
        Path cache = shortTempDir();
        Path agedKey = cache.resolve("actions/keys/aged");
        Files.createDirectories(agedKey.getParent());
        Files.writeString(agedKey, "INPUT deadbeef /x");
        Files.setLastModifiedTime(
                agedKey,
                FileTime.fromMillis(
                        System.currentTimeMillis() - Duration.ofDays(90).toMillis()));
        Path putTmp = cache.resolve("sha256").resolve(".put-1234");
        Files.createDirectories(putTmp.getParent());
        Files.writeString(putTmp, "partial");

        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        List<String> types = new ArrayList<>();
        String planFinish = null;
        String buildError = null;
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            c.sendLine(ProtoSession.cachePruneRequest("prune", cache.toString(), false, false));
            String line;
            while ((line = c.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                types.add(type);
                switch (type) {
                    case EngineProtocol.BUILDPLAN_FINISH -> planFinish = line;
                    case EngineProtocol.ERROR -> buildError = line;
                    default -> {
                        /* plan/progress events — presence asserted via `types` below */
                    }
                }
                if (planFinish != null || buildError != null) break;
            }
        }

        assertThat(buildError)
                .as("engine reported a pre-plan error instead of hosting the prune")
                .isNull();
        assertThat(types).contains(EngineProtocol.PLAN_TASK, EngineProtocol.PLAN_DONE);
        assertThat(planFinish).isNotNull();
        assertThat(Jsonl.bool(planFinish, "success", false)).isTrue();
        assertThat(Jsonl.longValue(planFinish, "cacheFiles", -1)).isEqualTo(1);
        assertThat(Jsonl.longValue(planFinish, "cacheBytes", -1)).isPositive();
        // The engine (not the client) swept the cache.
        assertThat(Files.exists(putTmp)).isFalse();
        assertThat(Files.exists(agedKey))
                .as("age alone is not an eviction criterion")
                .isTrue();
        assertThat(Files.readString(agedKey)).isEqualTo("INPUT deadbeef /x");
        // The cross-process guard exists: hosted maintenance always takes.prune.lock.
        assertThat(Files.exists(cache.resolve(".prune.lock"))).isTrue();

        server.close();
        serverThread.join(5_000);
    }

    /**
     * Engine-hosted {@code jk clean --force} round-trip: a real server over the socket invalidates the
     * action-cache entries for a fixture project, matching a record by its qualified-task tag while
     * leaving an unrelated project's record untouched. Also asserts the {@code.prune.lock} guard.
     */
    @Test
    void cache_clear_request_invalidates_the_projects_entries_over_the_socket() throws Exception {
        Path cache = shortTempDir();
        Path project = shortTempDir();
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name  = "proj"
                version = "0.1.0"
                java = 25
                """);
        // Cache clear realpaths the module root (matches BuildCommand); seed tags the same way.
        Path projectReal = project.toRealPath();
        String tag = ActionKey.taskTag(BuildLayout.of(projectReal, JkBuildParser.parse(projectReal.resolve("jk.toml")))
                .classesDir());
        Path mine = cache.resolve("actions/keys/mine");
        Files.createDirectories(mine.getParent());
        Files.writeString(mine, "TASK compile-main@" + tag + "\nKEY mine\nOUTPUT deadbeef foo.class\n");
        Files.createDirectories(cache.resolve("actions/tasks"));
        Files.writeString(cache.resolve("actions/tasks/compile-main@" + tag), "mine");
        Path other = cache.resolve("actions/keys/other");
        Files.writeString(other, "TASK compile-main@ffffffffffff\nKEY other\nOUTPUT deadbeef foo.class\n");

        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        String planFinish = null;
        String buildError = null;
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            c.sendLine(ProtoSession.cacheClearRequest(cache.toString(), project.toString(), false));
            String line;
            while ((line = c.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                switch (type) {
                    case EngineProtocol.BUILDPLAN_FINISH -> planFinish = line;
                    case EngineProtocol.ERROR -> buildError = line;
                    default -> {
                        /* plan/progress events */
                    }
                }
                if (planFinish != null || buildError != null) break;
            }
        }

        assertThat(buildError).isNull();
        assertThat(planFinish).isNotNull();
        assertThat(Jsonl.bool(planFinish, "success", false)).isTrue();
        assertThat(Jsonl.longValue(planFinish, "cacheFiles", -1)).isEqualTo(2); // record + pointer
        assertThat(Files.exists(mine)).isFalse();
        assertThat(Files.exists(cache.resolve("actions/tasks/compile-main@" + tag)))
                .isFalse();
        assertThat(Files.exists(other)).isTrue();
        assertThat(Files.exists(cache.resolve(".prune.lock"))).isTrue();

        server.close();
        serverThread.join(5_000);
    }
}
