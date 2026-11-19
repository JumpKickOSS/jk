// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static cc.jumpkick.engine.RemoteCacheE2eTest.build;
import static cc.jumpkick.engine.RemoteCacheE2eTest.checkout;
import static cc.jumpkick.engine.RemoteCacheE2eTest.copy;
import static cc.jumpkick.engine.RemoteCacheE2eTest.forgetReads;
import static cc.jumpkick.engine.RemoteCacheE2eTest.service;
import static cc.jumpkick.engine.RemoteCacheE2eTest.settled;
import static cc.jumpkick.engine.RemoteCacheE2eTest.succeeded;
import static cc.jumpkick.engine.RemoteCacheE2eTest.suite;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.testing.FakeCacheServer;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.run.StepCache;
import cc.jumpkick.task.CacheSeeds;
import cc.jumpkick.task.RemoteTier;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A suite that reads a variable its module never declares, shared between homes through jk's
 * {@code FakeCacheServer} by the cache's key seed: with the seed the cache issues, or
 * the one the team sets, another home with the same value replays the result, another value runs
 * it, a home without the seed runs it; a credential-looking name shares under the seed too, and
 * stays on the host under {@code JK_CACHE_CREDENTIAL_ENV=local}. Each home is its own checkout,
 * action cache and shell; the workspace and helpers are {@link RemoteCacheE2eTest}'s. The seeds
 * and values are made up.
 */
@Tag("integration")
class CacheSeedShareE2eTest extends EngineServerHarness {

    /** A suite that reads one variable no manifest declares, and passes whatever its value. */
    private static final String READS_MODE = """
            package com.example;
            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;
            class OneTest {
                @Test
                void answers() {
                    Assertions.assertNotNull(System.getenv("JKSEED_MODE"));
                    Assertions.assertEquals(42, One.answer());
                }
            }
            """;

    /** As {@link #READS_MODE}, reading a variable whose name says credential too. */
    private static final String READS_A_TOKEN = READS_MODE.replace(
            "Assertions.assertNotNull(System.getenv(\"JKSEED_MODE\"));",
            "Assertions.assertNotNull(System.getenv(\"JKSEED_MODE\"));\n        System.getenv(\"JKSEED_API_TOKEN\");");

    /** A suite that fails if its JVM holds the cache key seed, by name or in a listing. */
    private static final String SEES_NO_SEED = """
            package com.example;
            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;
            class OneTest {
                @Test
                void answers() {
                    Assertions.assertNull(System.getenv("JK_CACHE_KEY_SEED"));
                    Assertions.assertFalse(System.getenv().containsKey("JK_CACHE_KEY_SEED"));
                    Assertions.assertEquals(42, One.answer());
                }
            }
            """;

    private static final byte[] ISSUED = "made-up-issued-seed-for-the-e2e-0123".getBytes(StandardCharsets.UTF_8);

    private EnginePaths.Paths engine;
    private EngineServer server;
    private Thread serverThread;

    @BeforeEach
    void startEngine() throws Exception {
        engine = paths(shortTempDir());
        server = new EngineServer(engine, JkEngineConfig.DEFAULTS, JkVersion.VERSION, null);
        serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(10), () -> Files.exists(EnginePaths.endpoint(engine)));
    }

    @AfterEach
    void stopEngine() throws Exception {
        server.close();
        serverThread.join(10_000);
        LockfileReader.clearCache();
    }

    private static Map<String, String> shell(FakeCacheServer service, String token, boolean writes, String... vars) {
        Map<String, String> shell = new HashMap<>(service(service, token, writes));
        for (int i = 0; i < vars.length; i += 2) shell.put(vars[i], vars[i + 1]);
        return shell;
    }

    /** Drop the seeds this home keeps, as another host has none: every build here shares one home. */
    private static void forgetSeeds() throws IOException {
        PathUtil.deleteRecursively(JkDirs.state().resolve("cache-seeds"));
    }

    private StepCache.Outcome runAt(Path ws, Path cache, Map<String, String> shell) throws Exception {
        List<String> built = build(engine, ws, cache, shell, true);
        assertThat(succeeded(built)).as(ws + " builds: " + built).isTrue();
        return suite(settled(ws)).outcome();
    }

    @Test
    void with_the_issued_seed_another_home_replays_with_the_same_value_and_runs_with_another_or_without_it()
            throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), READS_MODE);
        Path b = copy(a, root.resolve("b"));
        Path c = copy(a, root.resolve("c"));
        Path d = copy(a, root.resolve("d"));
        try (FakeCacheServer service = FakeCacheServer.start().issueSeed("fake-project:1", ISSUED)) {
            runAt(a, root.resolve("cache-a"), shell(service, FakeCacheServer.WRITER, true, "JKSEED_MODE", "one"));
            assertThat(service.calls()).contains("GET /v0/key-seed writer");
            assertThat(service.recordsOf("test-marker")).singleElement().satisfies(m -> {
                assertThat(m.text())
                        .contains("INPUT - x:env-read:JKSEED_MODE\n")
                        .doesNotContain("x:env-keyed")
                        .doesNotContain("x:env-seed")
                        .doesNotContain(" one\n")
                        .doesNotContain("made-up-issued-seed");
                assertThat(MiniJson.list(m.reads(), "files"))
                        .map(String::valueOf)
                        .contains("env:JKSEED_MODE");
            });
            assertThat(service.recordsOf("class-ledger"))
                    .as("the ledger went up under its shared form too")
                    .singleElement()
                    .satisfies(l -> assertThat(l.text()).doesNotContain("x:env-keyed"));

            forgetReads(a);
            StepCache.Outcome same = runAt(
                    b, root.resolve("cache-b"), shell(service, FakeCacheServer.READER, false, "JKSEED_MODE", "one"));
            assertThat(service.byReadsServed())
                    .as("B found the result by its read list")
                    .isPositive();
            assertThat(same)
                    .as("B, with the same seed and value, replayed A's result")
                    .isNotEqualTo(StepCache.Outcome.MISS);

            forgetReads(b);
            assertThat(runAt(
                            c,
                            root.resolve("cache-c"),
                            shell(service, FakeCacheServer.READER, false, "JKSEED_MODE", "two")))
                    .as("another value runs")
                    .isEqualTo(StepCache.Outcome.MISS);

            forgetReads(c);
            assertThat(runAt(
                            d,
                            root.resolve("cache-d"),
                            shell(service, FakeCacheServer.READER, false, "JKSEED_MODE", "one", CacheSeeds.ENV, "off")))
                    .as("a home without the seed runs")
                    .isEqualTo(StepCache.Outcome.MISS);
        }
    }

    @Test
    void a_team_seed_shares_through_a_cache_that_issues_none_and_without_one_nothing_leaves() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), READS_MODE);
        Path a2 = copy(a, root.resolve("a2"));
        Path b = copy(a, root.resolve("b"));
        try (FakeCacheServer service = FakeCacheServer.start()) {
            runAt(a, root.resolve("cache-a"), shell(service, FakeCacheServer.WRITER, true, "JKSEED_MODE", "one"));
            assertThat(service.recordsOf("test-marker"))
                    .as("no seed: a result that read the shell stays on its host")
                    .isEmpty();

            forgetReads(a);
            String team = "made-up-team-seed-for-the-e2e";
            runAt(
                    a2,
                    root.resolve("cache-a2"),
                    shell(service, FakeCacheServer.WRITER, true, "JKSEED_MODE", "one", CacheSeeds.ENV, team));
            assertThat(service.recordsOf("test-marker")).hasSize(1);

            forgetReads(a2);
            assertThat(runAt(
                            b,
                            root.resolve("cache-b"),
                            shell(service, FakeCacheServer.READER, false, "JKSEED_MODE", "one", CacheSeeds.ENV, team)))
                    .as("the same team seed replays")
                    .isNotEqualTo(StepCache.Outcome.MISS);
        }
    }

    @Test
    void a_credential_looking_name_shares_under_the_seed_and_stays_on_the_host_under_the_opt_out() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), READS_A_TOKEN);
        Path b = copy(a, root.resolve("b"));
        Path c = copy(a, root.resolve("c"));
        String token = "made-up-api-token";
        try (FakeCacheServer service = FakeCacheServer.start().issueSeed("fake-project:1", ISSUED)) {
            runAt(
                    a,
                    root.resolve("cache-a"),
                    shell(
                            service,
                            FakeCacheServer.WRITER,
                            true,
                            "JKSEED_MODE",
                            "one",
                            "JKSEED_API_TOKEN",
                            token,
                            CacheSeeds.CREDENTIAL_ENV,
                            "local"));
            assertThat(service.recordsOf("test-marker"))
                    .as("kept here: the user keeps results keyed on a credential on this host")
                    .isEmpty();
            assertThat(service.recordsOf("class-ledger")).isEmpty();

            forgetReads(a);
            runAt(
                    b,
                    root.resolve("cache-b"),
                    shell(service, FakeCacheServer.WRITER, true, "JKSEED_MODE", "one", "JKSEED_API_TOKEN", token));
            assertThat(service.recordsOf("test-marker")).singleElement().satisfies(m -> assertThat(m.text())
                    .contains("INPUT - x:env-read:JKSEED_API_TOKEN\n")
                    .doesNotContain(token)
                    .doesNotContain("x:env-keyed"));

            forgetReads(b);
            assertThat(runAt(
                            c,
                            root.resolve("cache-c"),
                            shell(
                                    service,
                                    FakeCacheServer.READER,
                                    false,
                                    "JKSEED_MODE",
                                    "one",
                                    "JKSEED_API_TOKEN",
                                    token)))
                    .as("the same credential, the same seed: replayed, as a CI runner's result is")
                    .isNotEqualTo(StepCache.Outcome.MISS);
        }
    }

    /**
     * A module that inherits the caller's whole shell hands its compiler and test JVM no seed, and a
     * new seed reruns nothing: no key holds it. (A manifest that names the seed is refused at parse.)
     */
    @Test
    void the_team_seed_reaches_no_worker_of_an_inheriting_module_and_keys_nothing() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), SEES_NO_SEED, "\n[env]\ninherit = true\n");
        try (FakeCacheServer service = FakeCacheServer.start()) {
            runAt(
                    a,
                    root.resolve("cache-a"),
                    shell(service, FakeCacheServer.WRITER, true, CacheSeeds.ENV, "made-up-team-seed-for-the-e2e"));
            List<String> again = build(
                    engine,
                    a,
                    root.resolve("cache-a"),
                    shell(service, FakeCacheServer.WRITER, true, CacheSeeds.ENV, "made-up-other-seed-for-the-e2e"),
                    true);
            assertThat(succeeded(again)).as("builds again: " + again).isTrue();
            List<String> finished = again.stream()
                    .filter(l -> EngineProtocol.TASK_FINISH.equals(EngineProtocol.typeOf(l)))
                    .toList();
            assertThat(finished)
                    .as("another seed: no compile and no test runs again")
                    .noneMatch(l -> "SUCCESS".equals(Jsonl.str(l, "status"))
                            && List.of("compile", "run-tests").contains(Jsonl.str(l, "task")));
        }
    }

    /**
     * A cache that does not hand its seed to an identity that may only read (a fork's CI build)
     * answers it 404: that build runs the suite the writer shared and says nothing of a seed, as
     * with a cache that issues none. A refusal, by contrast, is said.
     */
    @Test
    void a_reader_the_cache_answers_404_runs_the_suite_and_says_nothing() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), READS_MODE);
        Path b = copy(a, root.resolve("b"));
        Path c = copy(a, root.resolve("c"));
        try (FakeCacheServer service = FakeCacheServer.start().issueSeedToWritersOnly("fake-org:1", ISSUED)) {
            runAt(a, root.resolve("cache-a"), shell(service, FakeCacheServer.WRITER, true, "JKSEED_MODE", "one"));
            assertThat(service.recordsOf("test-marker"))
                    .as("the writer shared it")
                    .hasSize(1);

            forgetReads(a);
            forgetSeeds();
            List<String> built = build(
                    engine,
                    b,
                    root.resolve("cache-b"),
                    shell(service, FakeCacheServer.READER, false, "JKSEED_MODE", "one"),
                    true);
            assertThat(succeeded(built)).as("B builds: " + built).isTrue();
            assertThat(service.calls()).contains("GET /v0/key-seed reader");
            assertThat(suite(settled(b)).outcome()).as("no seed: B runs it").isEqualTo(StepCache.Outcome.MISS);
            assertThat(built)
                    .as("and says nothing of the seed")
                    .noneMatch(l -> l.contains(RemoteTier.SEED_UNAVAILABLE) || l.contains("key seed"));

            service.failSeed(503);
            forgetSeeds();
            List<String> refused = build(
                    engine,
                    c,
                    root.resolve("cache-c"),
                    shell(service, FakeCacheServer.READER, false, "JKSEED_MODE", "one"),
                    true);
            assertThat(refused).as("a refusal is said").anyMatch(l -> l.contains("did not hand out its key seed"));
        }
    }
}
