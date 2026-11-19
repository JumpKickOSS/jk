// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static cc.jumpkick.engine.RemoteCacheE2eTest.build;
import static cc.jumpkick.engine.RemoteCacheE2eTest.checkout;
import static cc.jumpkick.engine.RemoteCacheE2eTest.copy;
import static cc.jumpkick.engine.RemoteCacheE2eTest.forgetReads;
import static cc.jumpkick.engine.RemoteCacheE2eTest.service;
import static cc.jumpkick.engine.RemoteCacheE2eTest.settled;
import static cc.jumpkick.engine.RemoteCacheE2eTest.succeeded;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.testing.FakeCacheServer;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.run.StepCache;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.CacheSeeds;
import cc.jumpkick.task.PlainDigests;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A module that forwards a variable of the caller's shell to its compiler ({@code [env]} with a
 * compile that may run processors) keys its compile, and the reference index cached beside it, on
 * the value sealed: no plain digest of it is kept here or sent, two homes with other home secrets
 * and no seed never share that compile, two homes with one team seed and the same value do, another
 * value compiles, and the jar, which reads no environment, shares either way. Each
 * home is its own checkout, action cache, shell and home secret ({@code state/env-read.key} is made
 * anew); the workspace and helpers are {@link RemoteCacheE2eTest}'s. Seeds and values are made up.
 */
@Tag("integration")
class EnvStepShareE2eTest extends EngineServerHarness {

    private static final String NAME = "JKEK_MODE";

    private static final String VALUE = "made-up-jkek-value-one";

    /** The task a main compile's record names: {@code compile-main@<tag>}. */
    private static final String COMPILE = "compile-main";

    private static final String TEAM = "made-up-team-seed-for-env-keys-e2e";

    /** The suite: it reads nothing, so only the compile and the jar carry the variable's value. */
    private static final String TEST = """
            package com.example;
            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;
            class OneTest {
                @Test
                void answers() {
                    Assertions.assertEquals(42, One.answer());
                }
            }
            """;

    /** lib forwards {@link #NAME} to its workers, and its compile may run processors, which could read it. */
    private static final String LIB = """

            [env]
            require = ["JKEK_MODE"]

            [javac]
            args = ["-proc:full"]
            """;

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

    @Test
    void the_compile_shares_under_one_seed_alone_and_no_digest_of_the_value_is_kept_or_sent() throws Exception {
        Path root = shortTempDir();
        Path n1 = checkout(root.resolve("n1"), TEST, LIB);
        Path n2 = copy(n1, root.resolve("n2"));
        Path a = copy(n1, root.resolve("a"));
        Path b = copy(n1, root.resolve("b"));
        Path c = copy(n1, root.resolve("c"));
        try (FakeCacheServer service = FakeCacheServer.start()) {
            BuildRecord first = runAt(n1, root.resolve("cache-n1"), shell(service, FakeCacheServer.WRITER, true));
            assertThat(outcome(first, TaskNames.COMPILE_JAVA)).isEqualTo(StepCache.Outcome.MISS);
            assertThat(held(service, COMPILE))
                    .as("no seed: app's compile went up, lib's, keyed on the value, did not: " + tasks(service))
                    .isEqualTo(1);
            assertThat(held(service, COMPILE + "@", "+references"))
                    .as("nor the reference index cached beside it: " + tasks(service))
                    .isEqualTo(1);

            newHomeSecret(n1);
            BuildRecord second = runAt(n2, root.resolve("cache-n2"), shell(service, FakeCacheServer.WRITER, true));
            assertThat(outcome(second, TaskNames.COMPILE_JAVA))
                    .as("no seed: another home secret does not take the first home's compile")
                    .isEqualTo(StepCache.Outcome.MISS);
            assertThat(outcome(second, TaskNames.PACKAGE_JAR))
                    .as("the jar reads no environment and its key is the same")
                    .isEqualTo(StepCache.Outcome.REMOTE_HIT);
            assertThat(held(service, COMPILE))
                    .as("no seed: neither home sent a compile keyed on the value")
                    .isEqualTo(1);

            newHomeSecret(n2);
            runAt(a, root.resolve("cache-a"), shell(service, FakeCacheServer.WRITER, true, CacheSeeds.ENV, TEAM));
            assertThat(held(service, COMPILE))
                    .as("under the seed lib's compile went up")
                    .isEqualTo(2);
            assertThat(held(service, COMPILE + "@", "+references")).isEqualTo(2);

            newHomeSecret(a);
            BuildRecord same = runAt(
                    b, root.resolve("cache-b"), shell(service, FakeCacheServer.READER, false, CacheSeeds.ENV, TEAM));
            assertThat(outcome(same, TaskNames.COMPILE_JAVA))
                    .as("one seed and the same value: B takes A's compile")
                    .isEqualTo(StepCache.Outcome.REMOTE_HIT);

            newHomeSecret(b);
            Map<String, String> other = shell(service, FakeCacheServer.READER, false, CacheSeeds.ENV, TEAM);
            other.put(NAME, "made-up-jkek-value-two");
            assertThat(outcome(runAt(c, root.resolve("cache-c"), other), TaskNames.COMPILE_JAVA))
                    .as("another value compiles")
                    .isEqualTo(StepCache.Outcome.MISS);

            StringBuilder sent = new StringBuilder();
            service.records()
                    .values()
                    .forEach(list -> list.forEach(s -> sent.append(s.text()).append('\n')));
            PlainDigests.assertNone(sent.toString(), VALUE);
            assertThat(sent.toString()).doesNotContain(VALUE).doesNotContain(TEAM);
            for (Path kept : List.of(
                    root.resolve("cache-n1"),
                    root.resolve("cache-a"),
                    n1.resolve("lib/target"),
                    a.resolve("lib/target"))) {
                assertThat(holdingADigest(kept, VALUE)).as("kept under " + kept).isEmpty();
            }
        }
    }

    private Map<String, String> shell(FakeCacheServer service, String token, boolean writes, String... vars) {
        Map<String, String> shell = new HashMap<>(service(service, token, writes));
        shell.put(NAME, VALUE);
        for (int i = 0; i < vars.length; i += 2) shell.put(vars[i], vars[i + 1]);
        return shell;
    }

    private BuildRecord runAt(Path ws, Path cache, Map<String, String> shell) throws Exception {
        List<String> built = build(engine, ws, cache, shell, true);
        assertThat(succeeded(built)).as(ws + " builds: " + built).isTrue();
        return settled(ws);
    }

    /**
     * Another host: this harness shares one jk home, so the home's secret is made anew, and what
     * {@code ws}'s suite was found to read is dropped.
     */
    private static void newHomeSecret(Path ws) throws IOException {
        Files.deleteIfExists(JkDirs.state().resolve("env-read.key"));
        forgetReads(ws);
    }

    /** How many records the cache holds of {@code task} (its id up to the tag), any module's. */
    private static long held(FakeCacheServer service, String task) {
        return held(service, task + "@", null);
    }

    /** How many records the cache holds whose task starts {@code prefix} and ends {@code suffix} (any end when null). */
    private static long held(FakeCacheServer service, String prefix, @Nullable String suffix) {
        return tasks(service).stream()
                .map(t -> t.substring("TASK ".length()))
                .filter(t -> t.startsWith(prefix) && (suffix == null ? !t.contains("+") : t.endsWith(suffix)))
                .count();
    }

    /** The task of every record the cache holds, for a message. */
    private static List<String> tasks(FakeCacheServer service) {
        List<String> out = new ArrayList<>();
        service.records()
                .values()
                .forEach(list -> list.forEach(r -> out.add(r.text()
                        .lines()
                        .filter(l -> l.startsWith("TASK "))
                        .findFirst()
                        .orElse("?"))));
        return out;
    }

    /** What the action cache said for lib's {@code step}. */
    private static StepCache.Outcome outcome(BuildRecord record, String step) {
        for (BuildRecord.Module m : record.modules()) {
            if (!Path.of(m.dir()).getFileName().toString().equals("lib")) continue;
            for (BuildRecord.Task t : m.steps()) {
                if (t.name().equals(step) && t.cache() != null) return t.cache().outcome();
            }
        }
        throw new AssertionError("lib's " + step + " consulted the cache: " + record.modules());
    }

    /** The files under {@code dir}, below a size a key, record or memo stays under, holding a plain digest of {@code value}. */
    private static List<Path> holdingADigest(Path dir, String value) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        String[] spellings = PlainDigests.of(value);
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                if (Files.size(f) > 4 * 1024 * 1024) continue;
                String text;
                try (InputStream in = Files.newInputStream(f)) {
                    text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                }
                for (String s : spellings) {
                    if (text.contains(s)) {
                        out.add(f);
                        break;
                    }
                }
            }
        }
        return out;
    }
}
