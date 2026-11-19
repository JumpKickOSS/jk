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
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.run.StepCache;
import cc.jumpkick.task.CacheSeeds;
import cc.jumpkick.test.SuiteEnvReads;
import cc.jumpkick.test.SuiteReads;
import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A suite that reads the caller's whole shell, as a Spring Boot suite does, between two homes and
 * jk's {@code FakeCacheServer} (JK-2M8VG): under {@code [env] scope} and a team's key seed another
 * user with another token replays it; with no seed its result stays on the host that ran it, scoped
 * or not. The workspace, shells and helpers are {@link RemoteCacheE2eTest}'s; the seed
 * is made up.
 */
@Tag("integration")
class ScopedEnvShareE2eTest extends EngineServerHarness {

    /**
     * A suite that reads the shell as Spring Boot binds it, every name and then every value, and
     * two variables by name, and needs one the scope lets through.
     */
    private static final String READS_SHELL = """
            package com.example;
            import java.util.Map;
            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;
            class OneTest {
                @Test
                void answers() {
                    int values = 0;
                    for (Map.Entry<String, String> e : System.getenv().entrySet()) {
                        if (e.getValue() != null) values++;
                    }
                    Assertions.assertTrue(values > 0);
                    System.getenv("USER");
                    System.getenv("TOKFILE");
                    Assertions.assertEquals("on", System.getenv("JKSCOPE_MODE"));
                    Assertions.assertEquals(42, One.answer());
                }
            }
            """;

    /** The team's seed both homes set. */
    private static final String SEED = "made-up-team-seed-for-the-scoped-e2e";

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

    /**
     * A scoped suite that reads the whole shell is shared and replayed by a home with another user,
     * token file and cache token (JK-2M8VG): the names its module's scope hides key nothing, even
     * the ones a run before the scope kept, and its read list names what it did read so the other
     * home keys it alike under the team's seed. The dojo-5 study's spring-petclinic, in small.
     */
    @Test
    void a_scoped_suite_that_reads_the_whole_shell_is_replayed_by_another_user_with_another_token() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), READS_SHELL, "\n[env]\nscope = [\"JKSCOPE_*\"]\n");
        Path b = copy(a, root.resolve("b"));
        try (FakeCacheServer service = FakeCacheServer.start()) {
            String token = RepoCredentialResolver.envVarPrefix(service.hostId()) + "TOKEN";
            // What a run before the scope left kept: the user, the token file and the cache token.
            SuiteReads.save(
                    a.resolve("lib"),
                    TestSelection.DEFAULT,
                    new SuiteReads.Discovered(
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            new SuiteEnvReads.Record(Set.of("USER", "TOKFILE", token), true, false)));
            Map<String, String> writer = new HashMap<>(service(service, FakeCacheServer.WRITER, true));
            writer.putAll(
                    Map.of("USER", "alice", "TOKFILE", "/var/tmp/a/tok", "JKSCOPE_MODE", "on", CacheSeeds.ENV, SEED));
            List<String> built = build(engine, a, root.resolve("cache-a"), writer, true);
            assertThat(succeeded(built)).as("A builds: " + built).isTrue();
            settled(a);
            assertThat(service.recordsOf("test-marker")).singleElement().satisfies(m -> {
                assertThat(m.text())
                        .contains("INPUT - x:env-read:JKSCOPE_MODE\n")
                        .contains("x:env-names")
                        .doesNotContain("x:env-read:USER")
                        .doesNotContain("x:env-read:TOKFILE")
                        .doesNotContain(token)
                        .doesNotContain(SEED)
                        .doesNotContain("x:env-keyed");
                assertThat(MiniJson.list(m.reads(), "files"))
                        .map(String::valueOf)
                        .contains("env:JKSCOPE_MODE", "env-list:names")
                        .noneMatch(n -> n.equals("env:USER") || n.equals("env:TOKFILE") || n.contains(token));
            });
            forgetReads(a);

            Map<String, String> reader = new HashMap<>(service(service, FakeCacheServer.READER, false));
            reader.putAll(
                    Map.of("USER", "bob", "TOKFILE", "/var/tmp/b/tok", "JKSCOPE_MODE", "on", CacheSeeds.ENV, SEED));
            List<String> again = build(engine, b, root.resolve("cache-b"), reader, true);
            assertThat(succeeded(again)).as("B builds: " + again).isTrue();
            BuildRecord record = settled(b);
            assertThat(service.byReadsServed())
                    .as("B found the result by its read list")
                    .isPositive();
            assertThat(suite(record).outcome()).as("B replayed A's result").isNotEqualTo(StepCache.Outcome.MISS);
        }
    }

    /**
     * The same suite with no scope keys on what it read, by this home's secret: its result stays on
     * this host and is not uploaded, and another user runs it (JK-2M8VG's note).
     */
    @Test
    void an_unscoped_suite_that_reads_the_whole_shell_stays_on_its_host_and_is_not_uploaded() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), READS_SHELL);
        Path b = copy(a, root.resolve("b"));
        try (FakeCacheServer service = FakeCacheServer.start()) {
            Map<String, String> writer = new HashMap<>(service(service, FakeCacheServer.WRITER, true));
            writer.putAll(Map.of("USER", "alice", "TOKFILE", "/var/tmp/a/tok", "JKSCOPE_MODE", "on"));
            List<String> built = build(engine, a, root.resolve("cache-a"), writer, true);
            assertThat(succeeded(built)).as("A builds: " + built).isTrue();
            settled(a);
            assertThat(service.recordsOf("test-marker"))
                    .as("A uploaded no result keyed on its home")
                    .isEmpty();
            assertThat(service.recordsOf("class-ledger"))
                    .as("nor the ledger under that key")
                    .isEmpty();
            forgetReads(a);

            Map<String, String> reader = new HashMap<>(service(service, FakeCacheServer.READER, false));
            reader.putAll(Map.of("USER", "bob", "TOKFILE", "/var/tmp/b/tok", "JKSCOPE_MODE", "on"));
            List<String> again = build(engine, b, root.resolve("cache-b"), reader, true);
            assertThat(succeeded(again)).as("B builds: " + again).isTrue();
            assertThat(suite(settled(b)).outcome()).isEqualTo(StepCache.Outcome.MISS);
        }
    }

    /**
     * The scoped suite with no seed, set or issued: its values key on this home's secret, as an
     * unscoped one's do, so its result is not uploaded and another user runs it. Before,
     * a scoped value keyed by plain digest and the result went up with no seed at all.
     */
    @Test
    void a_scoped_suite_with_no_seed_stays_on_its_host_and_is_not_uploaded() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), READS_SHELL, "\n[env]\nscope = [\"JKSCOPE_*\"]\n");
        Path b = copy(a, root.resolve("b"));
        try (FakeCacheServer service = FakeCacheServer.start()) {
            Map<String, String> writer = new HashMap<>(service(service, FakeCacheServer.WRITER, true));
            writer.putAll(Map.of("USER", "alice", "JKSCOPE_MODE", "on"));
            List<String> built = build(engine, a, root.resolve("cache-a"), writer, true);
            assertThat(succeeded(built)).as("A builds: " + built).isTrue();
            settled(a);
            assertThat(service.recordsOf("test-marker"))
                    .as("no seed: nothing keyed on a value leaves")
                    .isEmpty();
            assertThat(service.recordsOf("class-ledger")).isEmpty();
            assertThat(service.calls()).as("asked whether the cache issues one").contains("GET /v0/key-seed writer");
            forgetReads(a);

            Map<String, String> reader = new HashMap<>(service(service, FakeCacheServer.READER, false));
            reader.putAll(Map.of("USER", "bob", "JKSCOPE_MODE", "on"));
            List<String> again = build(engine, b, root.resolve("cache-b"), reader, true);
            assertThat(succeeded(again)).as("B builds: " + again).isTrue();
            assertThat(suite(settled(b)).outcome()).isEqualTo(StepCache.Outcome.MISS);
        }
    }
}
