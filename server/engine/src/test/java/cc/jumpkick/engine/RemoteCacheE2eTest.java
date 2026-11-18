// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.testing.FakeCacheServer;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.StepCache;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.CachePlatform;
import cc.jumpkick.task.RemoteCacheBinding;
import cc.jumpkick.task.RemoteCacheSettings;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.BuildRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ExplainRequest;
import cc.jumpkick.wire.protocol.ExplainTaskEvent;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Two machines and a shared cache, through a real engine over its socket: machine A builds a
 * two-module workspace and uploads to a directory standing in for the shared store ({@link
 * RemoteCacheBinding}); machine B, another checkout path with its own cache, forecasts and builds
 * from what A left there. One engine serves both: what makes them two machines to the action cache
 * is the checkout path and the cache directory, which each request names. The artifact store (the
 * dependency jars) is this JVM's one store, so these tests say nothing about a home's path.
 *
 * <p>Network: the JUnit pins come from Maven Central into a cache under {@code build/} that
 * persists across runs.
 */
@Tag("integration")
class RemoteCacheE2eTest extends EngineServerHarness {

    private static final String GREEN = """
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

    private static final String RED = GREEN.replace("assertEquals(42", "assertEquals(41");

    /** A suite that also reads a file nothing declares: {@code data/fixture.txt} at the workspace root. */
    private static final String READS = """
            package com.example;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;
            class OneTest {
                @Test
                void answers() throws Exception {
                    Assertions.assertEquals(42, One.answer());
                    Assertions.assertEquals("one", Files.readString(Path.of("../data/fixture.txt")).strip());
                }
            }
            """;

    /** A suite that starts two POSIX utilities, {@code sh} and {@code sleep}. */
    private static final String STARTS = """
            package com.example;
            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;
            class OneTest {
                @Test
                void answers() throws Exception {
                    Assertions.assertEquals(42, One.answer());
                    Assertions.assertEquals(0, new ProcessBuilder("sh", "-c", "exit 0").start().waitFor());
                    Assertions.assertEquals(0, new ProcessBuilder("sleep", "0").start().waitFor());
                }
            }
            """;

    /** A suite that starts {@code jkfaketool}, whichever the PATH finds. */
    private static final String STARTS_TOOL = """
            package com.example;
            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;
            class OneTest {
                @Test
                void answers() throws Exception {
                    Assertions.assertEquals(42, One.answer());
                    Assertions.assertEquals(0, new ProcessBuilder("jkfaketool").start().waitFor());
                }
            }
            """;

    /** lib's test JVMs see the allow-list alone, as jk's own tree's do: a started program keys no shell. */
    private static final String STRICT = "\n[env]\ninherit = false\n";

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
    void a_second_machine_forecasts_and_builds_from_the_first_machines_records() throws Exception {
        Path root = shortTempDir();
        Path remote = Files.createDirectories(root.resolve("remote"));
        Path a = checkout(root.resolve("a"), GREEN);
        Path b = copy(a, root.resolve("b"));
        Path cacheA = root.resolve("cache-a");
        Path cacheB = root.resolve("cache-b");

        List<String> built = build(a, cacheA, writing(remote));
        assertThat(succeeded(built)).as("machine A builds: " + built).isTrue();
        settled(a);
        assertThat(recordsOfKind(remote, "outputs"))
                .as("A uploaded its outputs")
                .isNotEmpty();
        assertThat(recordsOfKind(remote, "test-marker"))
                .as("A uploaded its green marker")
                .hasSize(1);

        Map<String, ExplainTaskEvent> forecast = explain(b, cacheB, reading(remote));
        // B holds none of A's blobs: the keys that read the ABI of lib's classes (lib's test
        // compile, app's compile) come from the interface token A's records state.
        assertThat(forecast.values())
                .as("B's forecast prices every step as a hit from the remote: " + forecast.values())
                .filteredOn(t -> !t.name().equals("restore-outputs"))
                .hasSize(6)
                .allMatch(t -> t.status().equals("CACHED"));

        List<String> again = build(b, cacheB, reading(remote));
        assertThat(succeeded(again)).as("machine B builds: " + again).isTrue();
        BuildRecord record = settled(b);
        assertThat(b.resolve("lib/target/classes/com/example/One.class"))
                .as("B restored lib's classes")
                .isRegularFile();
        assertThat(b.resolve("app/target/classes/com/example/App.class"))
                .as("B restored app's classes")
                .isRegularFile();
        List<StepCache> remoteHits = cacheOf(record).stream()
                .filter(c -> c.outcome() == StepCache.Outcome.REMOTE_HIT)
                .toList();
        // A record with no blob (the suite's green marker) is kept here on its first lookup, which
        // B's explain made: the build reads it locally. Every step that restores outputs fetched
        // them from the remote.
        assertThat(cacheOf(record))
                .as("no step ran: " + record.modules())
                .isNotEmpty()
                .noneMatch(c -> c.outcome() == StepCache.Outcome.MISS)
                .filteredOn(c -> c.bytesIn() > 0)
                .isNotEmpty()
                .allMatch(c -> c.outcome() == StepCache.Outcome.REMOTE_HIT);
        assertThat(remoteHits).anyMatch(c -> c.bytesIn() > 0);
        assertThat(remoteHits).as("each hit says how long A took to make it").allMatch(c -> c.producerMillis() >= 0);
        BuildRecord.CacheBenefit benefit = requireNonNull(record.benefit(), "B's record prices what the remote saved");
        assertThat(benefit.coveredSkips())
                .as("hits with no history here are priced by the records: " + benefit)
                .isPositive();
        assertThat(requireNonNull(record.io(), "io").cacheDown())
                .as("bytes fetched from the remote")
                .isPositive();
    }

    @Test
    void a_red_suite_is_never_shared() throws Exception {
        Path root = shortTempDir();
        Path remote = Files.createDirectories(root.resolve("remote"));
        Path a = checkout(root.resolve("a"), RED);
        Path b = copy(a, root.resolve("b"));

        List<String> built = build(a, root.resolve("cache-a"), writing(remote));
        assertThat(succeeded(built)).as("A's suite is red").isFalse();
        settled(a);
        assertThat(recordsOfKind(remote, "outputs"))
                .as("A's compiles were shared")
                .isNotEmpty();
        assertThat(recordsOfKind(remote, "test-marker"))
                .as("its red marker was not")
                .isEmpty();

        List<String> again = build(b, root.resolve("cache-b"), reading(remote));
        assertThat(succeeded(again))
                .as("B runs the suite itself, and it is red")
                .isFalse();
        BuildRecord record = settled(b);
        assertThat(cacheOf(record))
                .as("B ran the suite: " + record.modules() + "\n" + again)
                .anyMatch(c -> c.outcome() == StepCache.Outcome.MISS);
    }

    @Test
    void a_record_made_for_another_target_is_not_taken_and_a_target_free_one_is() throws Exception {
        Path root = shortTempDir();
        Path remote = Files.createDirectories(root.resolve("remote"));
        Path a = checkout(root.resolve("a"), GREEN);
        Path b = copy(a, root.resolve("b"));

        assertThat(succeeded(build(a, root.resolve("cache-a"), writing(remote))))
                .isTrue();
        settled(a);
        List<Path> outputs = recordsOfKind(remote, "outputs");
        String other = CachePlatform.current().startsWith("linux") ? "windows-x86_64" : "linux-x86_64";
        for (Path r : outputs) target(r, other);

        Map<String, ExplainTaskEvent> elsewhere = explain(b, root.resolve("cache-b"), reading(remote));
        assertThat(status(elsewhere, "lib", "compile-java"))
                .as("outputs made for " + other + " are not for this platform: " + elsewhere.values())
                .isNotEqualTo("CACHED");

        for (Path r : outputs) target(r, CachePlatform.current());
        Map<String, ExplainTaskEvent> here = explain(b, root.resolve("cache-b2"), reading(remote));
        assertThat(status(here, "lib", "compile-java"))
                .as("outputs made for this platform are: " + here.values())
                .isEqualTo("CACHED");
    }

    @Test
    void a_remote_that_cannot_be_reached_is_off_for_the_build_with_one_warning() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), GREEN);

        List<String> built = build(a, root.resolve("cache-a"), reading(root.resolve("no-such-remote")));
        assertThat(succeeded(built)).as("the build succeeds locally: " + built).isTrue();
        BuildRecord record = settled(a);
        long warned = built.stream().filter(l -> l.contains("did not answer")).count();
        assertThat(warned)
                .as("one warning for the build: " + record.diagnostics() + "\n" + built)
                .isEqualTo(1);
        assertThat(cacheOf(record))
                .as("the steps ran, and say why: " + record.modules())
                .isNotEmpty()
                .allMatch(c -> c.outcome() == StepCache.Outcome.MISS);
    }

    /**
     * The same two machines through a cache service speaking the published protocol over HTTP: A, a
     * CI run, writes; B, a fork's pull request whose token reads only, builds clean from what A left
     * and asks the service for nothing but reads.
     */
    @Test
    void a_second_home_builds_from_a_shared_cache_service_and_a_reader_never_writes() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), GREEN);
        Path b = copy(a, root.resolve("b"));
        try (FakeCacheServer service = FakeCacheServer.start()) {
            List<String> built = build(a, root.resolve("cache-a"), service(service, FakeCacheServer.WRITER, true));
            assertThat(succeeded(built)).as("machine A builds: " + built).isTrue();
            settled(a);
            assertThat(service.recordsOf("outputs"))
                    .as("A uploaded its outputs")
                    .isNotEmpty();
            assertThat(service.recordsOf("test-marker"))
                    .as("A uploaded its green marker")
                    .hasSize(1);
            assertThat(service.calls()).anyMatch(c -> c.startsWith("PUT /v0/records/") && c.endsWith(" writer"));

            List<String> again = build(b, root.resolve("cache-b"), service(service, FakeCacheServer.READER, true));
            assertThat(succeeded(again)).as("machine B builds: " + again).isTrue();
            BuildRecord record = settled(b);
            assertThat(b.resolve("app/target/classes/com/example/App.class")).isRegularFile();
            assertThat(cacheOf(record))
                    .as("no step ran: " + record.modules())
                    .isNotEmpty()
                    .noneMatch(c -> c.outcome() == StepCache.Outcome.MISS)
                    .anyMatch(c -> c.outcome() == StepCache.Outcome.REMOTE_HIT && c.bytesIn() > 0);
            assertThat(cacheOf(record))
                    .as("a home whose cache started empty took every hit from the service, those the"
                            + " forecast's lookup kept on this disk too: " + record.modules())
                    .allMatch(c -> c.outcome() == StepCache.Outcome.REMOTE_HIT);
            assertThat(service.calls())
                    .as("the reader looked up and read, and sent nothing")
                    .filteredOn(c -> c.endsWith(" reader"))
                    .isNotEmpty()
                    .noneMatch(c -> c.startsWith("PUT ") || c.contains("upload") || c.contains("commit"));
            assertThat(service.lookups())
                    .as("a lookup names the reader's toolchain policy for the keys a step stated it for")
                    .anyMatch(l -> !MiniJson.list(l, "accept").isEmpty());
        }
    }

    @Test
    void a_pull_requests_run_never_writes_even_with_a_token_that_may() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), GREEN);
        try (FakeCacheServer service = FakeCacheServer.start()) {
            Map<String, String> env = new HashMap<>(service(service, FakeCacheServer.WRITER, true));
            env.put("CI", "true");
            env.put("GITHUB_EVENT_NAME", "pull_request");
            assertThat(succeeded(build(a, root.resolve("cache-a"), env))).isTrue();
            settled(a);
            assertThat(service.calls()).isNotEmpty().noneMatch(c -> c.startsWith("PUT ") || c.contains("upload"));
            assertThat(service.records()).isEmpty();
        }
    }

    @Test
    void a_blob_the_service_serves_altered_is_a_miss_and_the_step_runs() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), GREEN);
        Path b = copy(a, root.resolve("b"));
        try (FakeCacheServer service = FakeCacheServer.start()) {
            assertThat(succeeded(build(a, root.resolve("cache-a"), service(service, FakeCacheServer.WRITER, true))))
                    .isTrue();
            settled(a);
            for (String sha : service.blobs().keySet()) service.tamper(sha);

            List<String> again = build(b, root.resolve("cache-b"), service(service, FakeCacheServer.READER, false));
            assertThat(succeeded(again)).as("B builds locally: " + again).isTrue();
            BuildRecord record = settled(b);
            assertThat(cacheOf(record))
                    .as("every step whose blobs came altered ran: " + record.modules())
                    .filteredOn(c -> c.bytesIn() > 0)
                    .noneMatch(c -> c.outcome() == StepCache.Outcome.REMOTE_HIT);
            assertThat(cacheOf(record)).anyMatch(c -> c.outcome() == StepCache.Outcome.MISS);
            assertThat(b.resolve("lib/target/classes/com/example/One.class")).isRegularFile();
        }
    }

    @Test
    void a_cache_service_that_is_down_leaves_the_build_green_with_one_warning() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), GREEN);
        FakeCacheServer gone = FakeCacheServer.start();
        Map<String, String> env = service(gone, FakeCacheServer.WRITER, true);
        gone.close();

        List<String> built = build(a, root.resolve("cache-a"), env);
        assertThat(succeeded(built)).as("the build succeeds locally: " + built).isTrue();
        BuildRecord record = settled(a);
        assertThat(built.stream().filter(l -> l.contains("did not answer")).count())
                .as("one warning for the build: " + record.diagnostics() + "\n" + built)
                .isEqualTo(1);
        assertThat(cacheOf(record)).isNotEmpty().allMatch(c -> c.outcome() == StepCache.Outcome.MISS);
    }

    /**
     * One record the service will not take (503 on every put) is tried a bounded number of times
     * behind the build and left; every other record is stored, and the run says once, at its end,
     * what was not stored and why, with the counts in its history.
     */
    @Test
    void a_record_the_service_keeps_refusing_is_left_and_the_rest_are_stored_and_said() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), GREEN);
        Path b = copy(a, root.resolve("b"));
        try (FakeCacheServer whole = FakeCacheServer.start();
                FakeCacheServer flaky = FakeCacheServer.start().unavailableFor(Long.MAX_VALUE)) {
            assertThat(succeeded(build(a, root.resolve("cache-a"), service(whole, FakeCacheServer.WRITER, true))))
                    .isTrue();
            settled(a);
            int all = whole.records().size();

            List<String> built =
                    buildToJobFinish(b, root.resolve("cache-b"), service(flaky, FakeCacheServer.WRITER, true));
            assertThat(built)
                    .as("the build is green: " + built)
                    .anyMatch(l -> EngineProtocol.WORKSPACE_FINISH.equals(EngineProtocol.typeOf(l))
                            && Jsonl.bool(l, "success", false));
            BuildRecord record = settled(b);
            String refused = requireNonNull(flaky.unavailableKey(), "a record was put");
            assertThat(flaky.records())
                    .as("every record but the refused one is stored")
                    .hasSize(all - 1)
                    .doesNotContainKey(refused);
            assertThat(flaky.calls())
                    .as("the refused record was tried four times")
                    .filteredOn(c -> c.startsWith("PUT /v0/records/" + refused))
                    .hasSize(4);
            assertThat(built.stream().filter(l -> l.contains("not stored")).toList())
                    .as("one line, after the terminal and before the job's end, says what was not stored and why: "
                            + built)
                    .singleElement()
                    .satisfies(
                            l -> assertThat(l).contains("1 record not stored (1 503 from the service after 4 tries)"));
            assertThat(requireNonNull(record.io(), "io").cacheRecordsNotStored())
                    .isEqualTo(1);
        }
    }

    /**
     * A suite keyed by a file it was found to read: a host that never ran it computes its stamp
     * without that file, finds A's result by the read list filed under that stamp, recomputes the
     * stamp from its own copy of the file, and replays the result without starting a test JVM.
     */
    @Test
    void a_host_that_never_ran_a_suite_takes_its_result_by_read_list_and_runs_no_test() throws Exception {
        Path root = shortTempDir();
        Path a = withFixture(checkout(root.resolve("a"), READS), "one");
        Path b = copy(a, root.resolve("b"));
        try (FakeCacheServer service = FakeCacheServer.start()) {
            assertThat(succeeded(build(a, root.resolve("cache-a"), service(service, FakeCacheServer.WRITER, true))))
                    .isTrue();
            settled(a);
            assertThat(service.recordsOf("test-marker")).singleElement().satisfies(m -> {
                List<?> files = MiniJson.list(m.reads(), "files");
                assertThat(files).as("the list A filed with its result").hasSize(1);
                assertThat(String.valueOf(files.getFirst())).isEqualTo("ws:data/fixture.txt");
                assertThat(MiniJson.str(m.reads(), "base"))
                        .as("under the stamp without the read")
                        .isNotBlank();
            });
            assertThat(service.records().values().stream().flatMap(List::stream))
                    .as("no record names a checkout path")
                    .noneMatch(r -> r.text().contains(root.toString())
                            || String.valueOf(r.reads()).contains(root.toString()));
            forgetReads(a);

            List<String> again = build(b, root.resolve("cache-b"), service(service, FakeCacheServer.READER, false));
            assertThat(succeeded(again)).as("B builds: " + again).isTrue();
            BuildRecord record = settled(b);
            assertThat(service.byReadsServed())
                    .as("B found the result by its read list")
                    .isPositive();
            assertThat(suite(record).outcome())
                    .as("B replayed A's result: " + record.modules())
                    .isEqualTo(StepCache.Outcome.REMOTE_HIT);
        }
    }

    /**
     * A suite whose module forwards a variable the caller lacks and sets another is shared and
     * replayed: its record holds no U+0000, which the service's index refuses, and names each
     * variable without its value (JK-ANWXB).
     */
    @Test
    void a_suite_whose_module_forwards_an_unset_variable_is_shared_and_replayed_elsewhere() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(
                root.resolve("a"),
                GREEN,
                "\n[test]\nenv = [\"JK_ANWXB_NEVER_SET\", { SUITE_MODE = \"anwxb-literal\" }]\n");
        Path b = copy(a, root.resolve("b"));
        try (FakeCacheServer service = FakeCacheServer.start()) {
            assertThat(succeeded(build(a, root.resolve("cache-a"), service(service, FakeCacheServer.WRITER, true))))
                    .isTrue();
            settled(a);
            assertThat(service.recordsOf("test-marker"))
                    .as("A shared its suite's result")
                    .singleElement()
                    .satisfies(m -> assertThat(m.text())
                            .doesNotContain("\u0000")
                            .doesNotContain("anwxb-literal")
                            .contains("INPUT - x:test-env:JK_ANWXB_NEVER_SET\n")
                            .contains("INPUT - x:test-env:SUITE_MODE\n"));

            List<String> again = build(b, root.resolve("cache-b"), service(service, FakeCacheServer.READER, false));
            assertThat(succeeded(again)).as("B builds: " + again).isTrue();
            // A hit either way: one the forecast restored is told as local (JK-501G8).
            assertThat(suite(settled(b)).outcome()).as("B replayed A's result").isNotEqualTo(StepCache.Outcome.MISS);
        }
    }

    /**
     * A suite that starts {@code sh} and {@code sleep} sends its read list, programs named: a host
     * that never ran it takes the result by the list, keying both by name and OS family as the
     * writer did, and runs no test (JK-EV4F4: the dojo-4 study's same-machine misses).
     */
    @Test
    void a_host_that_never_ran_a_suite_that_starts_programs_takes_its_result_by_read_list() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), STARTS, STRICT);
        Path b = copy(a, root.resolve("b"));
        try (FakeCacheServer service = FakeCacheServer.start()) {
            assertThat(succeeded(build(a, root.resolve("cache-a"), service(service, FakeCacheServer.WRITER, true))))
                    .isTrue();
            settled(a);
            assertThat(service.recordsOf("test-marker")).singleElement().satisfies(m -> {
                assertThat(MiniJson.list(m.reads(), "files"))
                        .as("the list A filed names the programs")
                        .map(String::valueOf)
                        .contains("proc:sh", "proc:sleep");
                assertThat(m.text()).contains("INPUT posix x:proc:sh\n").contains("INPUT posix x:proc:sleep\n");
            });
            forgetReads(a);

            List<String> again = build(b, root.resolve("cache-b"), service(service, FakeCacheServer.READER, false));
            assertThat(succeeded(again)).as("B builds: " + again).isTrue();
            BuildRecord record = settled(b);
            assertThat(service.byReadsServed())
                    .as("B found the result by its read list")
                    .isPositive();
            assertThat(suite(record).outcome()).as("B replayed A's result").isNotEqualTo(StepCache.Outcome.MISS);
        }
    }

    /**
     * A host whose tool answers another version runs the suite, and its miss names the tool: the
     * result on offer was made with another one, which {@code no-record} hid (JK-EV4F4).
     */
    @Test
    void a_host_whose_tool_says_another_version_runs_the_suite_and_names_the_tool() throws Exception {
        Assumptions.assumeFalse(Os.isWindows(), "a shell script for a tool");
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), STARTS_TOOL, STRICT);
        Path b = copy(a, root.resolve("b"));
        try (FakeCacheServer service = FakeCacheServer.start()) {
            Map<String, String> writer = new HashMap<>(service(service, FakeCacheServer.WRITER, true));
            writer.put(
                    "PATH",
                    fakeTool(root.resolve("tools-a"), "jkfaketool 1.0") + File.pathSeparator + System.getenv("PATH"));
            assertThat(succeeded(build(a, root.resolve("cache-a"), writer))).isTrue();
            settled(a);
            assertThat(service.recordsOf("test-marker")).singleElement().satisfies(m -> assertThat(m.text())
                    .contains("INPUT jkfaketool%201.0 x:proc:jkfaketool\n"));
            forgetReads(a);

            Map<String, String> reader = new HashMap<>(service(service, FakeCacheServer.READER, false));
            reader.put(
                    "PATH",
                    fakeTool(root.resolve("tools-b"), "jkfaketool 2.0") + File.pathSeparator + System.getenv("PATH"));
            List<String> again = build(b, root.resolve("cache-b"), reader);
            assertThat(succeeded(again)).as("B builds: " + again).isTrue();
            StepCache miss = suite(settled(b));
            assertThat(service.byReadsServed()).as("B was offered A's result").isPositive();
            assertThat(miss.outcome()).isEqualTo(StepCache.Outcome.MISS);
            assertThat(miss.reason()).as(String.valueOf(miss)).isEqualTo(StepCache.Reason.INPUTS_CHANGED);
            assertThat(miss.changed()).as("names the tool").contains("x:proc:jkfaketool");
        }
    }

    /** A directory holding {@code jkfaketool}, which prints {@code version}. */
    private static Path fakeTool(Path dir, String version) throws IOException {
        Path tool = Files.createDirectories(dir).resolve("jkfaketool");
        Files.writeString(tool, "#!/bin/sh\necho '" + version + "'\n");
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwxr-xr-x"));
        return dir;
    }

    @Test
    void a_host_whose_copy_of_a_read_file_differs_runs_the_suite() throws Exception {
        Path root = shortTempDir();
        Path a = withFixture(checkout(root.resolve("a"), READS), "one");
        Path b = withFixture(copy(a, root.resolve("b")), "two");
        try (FakeCacheServer service = FakeCacheServer.start()) {
            assertThat(succeeded(build(a, root.resolve("cache-a"), service(service, FakeCacheServer.WRITER, true))))
                    .isTrue();
            settled(a);
            forgetReads(a);

            List<String> again = build(b, root.resolve("cache-b"), service(service, FakeCacheServer.READER, false));
            assertThat(succeeded(again))
                    .as("B ran the suite, and its file says two: " + again)
                    .isFalse();
            BuildRecord record = settled(b);
            assertThat(service.byReadsServed()).as("B was offered A's result").isPositive();
            assertThat(suite(record).outcome()).isEqualTo(StepCache.Outcome.MISS);
        }
    }

    /**
     * The property every shared hit rests on: the same sources at two checkout paths, each with its
     * own cache, store every step under the same action key.
     */
    @Test
    void the_same_sources_at_two_checkout_paths_store_the_same_action_keys() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), GREEN);
        Path b = copy(a, root.resolve("elsewhere/b"));
        Path cacheA = root.resolve("cache-a");
        Path cacheB = root.resolve("cache-b");

        assertThat(succeeded(build(a, cacheA, Map.of()))).isTrue();
        settled(a);
        assertThat(succeeded(build(b, cacheB, Map.of()))).isTrue();
        settled(b);

        Map<String, String> keysA = taskKeys(cacheA);
        Map<String, String> keysB = taskKeys(cacheB);
        assertThat(keysA).as("A stored records").isNotEmpty();
        assertThat(keysB).as("each task's key at B, against A's").isEqualTo(keysA);
    }

    /**
     * A build of a project with dependencies writes no record that names a path under the jk home
     * by its absolute path: dependency jars, worker jars and JDKs are named against their root
     * ({@code store:}, {@code cache:}, {@code jk-home:}), so the shared form keeps them.
     */
    @Test
    void no_record_names_a_path_under_the_jk_home_absolutely() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), GREEN);
        Path cache = root.resolve("cache-a");
        assertThat(succeeded(build(a, cache, Map.of()))).isTrue();
        settled(a);

        String home = JkDirs.home().toAbsolutePath().normalize().toString();
        String store = JkDirs.store().toAbsolutePath().normalize().toString();
        List<String> absolute = new ArrayList<>();
        int inputs = 0;
        try (Stream<Path> files = Files.list(ActionTree.KEYS.under(CacheTree.ACTIONS.under(cache)))) {
            for (Path f : files.toList()) {
                for (String line : Files.readAllLines(f)) {
                    if (!line.startsWith("INPUT ")) continue;
                    inputs++;
                    if (line.contains(home)
                            || line.contains(store)
                            || line.contains(cache.toString())
                            || line.contains(a.toString())) absolute.add(f.getFileName() + ": " + line);
                }
            }
        }
        assertThat(inputs).as("the build wrote records with inputs").isPositive();
        assertThat(absolute)
                .as("inputs naming the jk home, the store, the cache or the checkout absolutely")
                .isEmpty();
    }

    /** A suite's marker names its stamp's inputs, so the rerun after a test edit says which one moved. */
    @Test
    void a_suite_whose_test_source_changed_names_it_as_the_reason() throws Exception {
        Path root = shortTempDir();
        Path a = checkout(root.resolve("a"), GREEN);
        Path cache = root.resolve("cache-a");
        assertThat(succeeded(build(a, cache, Map.of()))).isTrue();
        long first = settled(a).buildNumber();

        Path test = a.resolve("lib/test/src/com/example/OneTest.java");
        Files.writeString(test, GREEN.replace("class OneTest {", "class OneTest {\n    // edited"));
        assertThat(succeeded(build(a, cache, Map.of()))).isTrue();
        BuildRecord record = settled(a, first);
        StepCache suite = null;
        for (BuildRecord.Module m : record.modules()) {
            for (BuildRecord.Task t : m.steps()) {
                if (t.name().equals("run-tests") && t.cache() != null) suite = t.cache();
            }
        }
        requireNonNull(suite, "run-tests consulted the cache: " + record.modules());
        assertThat(suite.outcome()).isEqualTo(StepCache.Outcome.MISS);
        assertThat(suite.reason()).as(String.valueOf(suite)).isEqualTo(StepCache.Reason.INPUTS_CHANGED);
        assertThat(suite.changed()).as("names the edited test source").contains("OneTest.java");
    }

    // ---- fixture -------------------------------------------------------------------------

    static Path checkout(Path ws, String test) throws Exception {
        return checkout(ws, test, "");
    }

    /** As {@link #checkout(Path, String)}, with {@code libToml} appended to lib's manifest. */
    static Path checkout(Path ws, String test, String libToml) throws Exception {
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        Files.createDirectories(ws.resolve("lib/src/com/example"));
        Files.writeString(ws.resolve("lib/jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """ + libToml);
        Files.writeString(ws.resolve("lib/src/com/example/One.java"), """
                package com.example;

                public final class One {
                    private One() {}

                    public static int answer() {
                        return 42;
                    }
                }
                """);
        Files.createDirectories(ws.resolve("lib/test/src/com/example"));
        Files.writeString(ws.resolve("lib/test/src/com/example/OneTest.java"), test);
        Files.createDirectories(ws.resolve("app/src/com/example"));
        Files.writeString(ws.resolve("app/jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { workspace = true }
                """);
        Files.writeString(ws.resolve("app/src/com/example/App.java"), """
                package com.example;

                public final class App {
                    private App() {}

                    public static void main(String[] args) {
                        System.out.println(One.answer());
                    }
                }
                """);
        BuildPlan lock = LockPlans.lockBuildPlan(
                ws, TestCaches.dir("remote-cache-e2e-lock"), null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        return ws;
    }

    /** {@code ws} with {@code data/fixture.txt} holding {@code text}. */
    private static Path withFixture(Path ws, String text) throws IOException {
        Files.writeString(Files.createDirectories(ws.resolve("data")).resolve("fixture.txt"), text);
        return ws;
    }

    /**
     * Drop what {@code ws}'s suite was found to read from this JVM's jk cache, where every checkout
     * of the project finds it: the next checkout is then a host that shares only the remote tier.
     */
    static void forgetReads(Path ws) throws IOException {
        PathUtil.deleteRecursively(CacheTree.PROJECTS
                .under(JkDirs.cache())
                .resolve("m-" + ActionKey.taskTag(ws.resolve("lib")))
                .resolve("suite-reads"));
    }

    /** What the action cache said for the run's one suite. */
    static StepCache suite(BuildRecord record) {
        StepCache found = null;
        for (BuildRecord.Module m : record.modules()) {
            for (BuildRecord.Task t : m.steps()) {
                if (t.name().equals("run-tests") && t.cache() != null) found = t.cache();
            }
        }
        return requireNonNull(found, "run-tests consulted the cache: " + record.modules());
    }

    /** {@code from}'s sources and lock at {@code to}: another checkout of the same commit. */
    static Path copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path src : files.toList()) {
                Path rel = from.relativize(src);
                if (rel.toString().contains("target")) continue;
                Path dst = to.resolve(rel.toString());
                if (Files.isDirectory(src)) Files.createDirectories(dst);
                else Files.copy(src, dst);
            }
        }
        return to;
    }

    /** The shell of a request using {@code service} with {@code token}, writing when {@code writes}. */
    static Map<String, String> service(FakeCacheServer service, String token, boolean writes) {
        return Map.of(
                RemoteCacheSettings.URL_ENV,
                service.url().toString(),
                RepoCredentialResolver.envVarPrefix(service.hostId()) + "TOKEN",
                token,
                RemoteCacheSettings.WRITE_ENV,
                String.valueOf(writes));
    }

    private static Map<String, String> writing(Path remote) {
        return Map.of(RemoteCacheBinding.DIR, remote.toString(), RemoteCacheBinding.WRITE, "true");
    }

    private static Map<String, String> reading(Path remote) {
        return Map.of(RemoteCacheBinding.DIR, remote.toString());
    }

    /**
     * The request's shell: the few variables a build here needs from this JVM's environment, and
     * {@code extra}; sent as the caller's whole shell, names and all, as the CLI sends it, when
     * {@code whole}.
     */
    private static String withShell(String request, Map<String, String> extra, boolean whole) {
        Map<String, String> shell = new HashMap<>();
        putIfSet(shell, "PATH", System.getenv("PATH"));
        putIfSet(shell, "HOME", System.getenv("HOME"));
        putIfSet(shell, "JAVA_HOME", System.getenv("JAVA_HOME"));
        shell.putAll(extra);
        String line = ProtoSession.withShell(request, shell);
        return whole
                ? ProtoSession.withShellFacts(
                        line, new ProtoSession.ShellFacts(List.of(), true, List.copyOf(shell.keySet()), false))
                : line;
    }

    private static void putIfSet(Map<String, String> shell, String name, @Nullable String value) {
        if (value != null) shell.put(name, value);
    }

    private List<String> build(Path ws, Path cache, Map<String, String> env) throws IOException {
        return build(engine, ws, cache, env, false);
    }

    /**
     * As {@link #build(Path, Path, Map)} through the engine at {@code engine}, sending {@code env} as
     * the caller's whole shell, names and all, when {@code whole}.
     */
    static List<String> build(EnginePaths.Paths engine, Path ws, Path cache, Map<String, String> env, boolean whole)
            throws IOException {
        return runToWorkspaceFinish(engine, withShell(buildRequest(ws, cache), env, whole));
    }

    private static String buildRequest(Path ws, Path cache) {
        return new BuildRequest(
                        ws.toString(),
                        cache.toString(),
                        null,
                        1,
                        null,
                        false,
                        false,
                        0,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null,
                        TestSelection.DEFAULT,
                        null,
                        List.of(),
                        false,
                        null,
                        Map.of(),
                        null,
                        null,
                        null)
                .encode();
    }

    /**
     * As {@link #build}, reading on past the terminal to {@code job-finish} as the CLI does: what the
     * job says as it winds down (its uploads draining) comes in between.
     */
    private List<String> buildToJobFinish(Path ws, Path cache, Map<String, String> env) throws IOException {
        List<String> lines = new ArrayList<>();
        try (Client c = new Client(EnginePaths.activeSocket(engine))) {
            c.sendLine(withShell(buildRequest(ws, cache), env, false));
            String line;
            while ((line = c.readLine(Duration.ofMinutes(3))) != null) {
                lines.add(line);
                String type = EngineProtocol.typeOf(line);
                if (EngineProtocol.JOB_FINISH.equals(type)) return lines;
                if (EngineProtocol.ERROR.equals(type)) throw new IOException("request failed: " + line);
            }
        }
        throw new IOException("disconnected before job-finish: " + lines);
    }

    static boolean succeeded(List<String> lines) {
        return Jsonl.bool(lines.getLast(), "success", false);
    }

    /** Every step {@code jk explain} forecasts for {@code ws}, by module directory name and step. */
    private Map<String, ExplainTaskEvent> explain(Path ws, Path cache, Map<String, String> env) throws IOException {
        String request = new ExplainRequest(
                        ws.toString(),
                        cache.toString(),
                        0,
                        false,
                        null,
                        null,
                        false,
                        false,
                        false,
                        false,
                        0,
                        List.of(),
                        TestSelection.DEFAULT,
                        false)
                .encode();
        Map<String, ExplainTaskEvent> steps = new TreeMap<>();
        try (Client c = new Client(EnginePaths.activeSocket(engine))) {
            c.sendLine(withShell(request, env, false));
            String line;
            while ((line = c.readLine(Duration.ofMinutes(3))) != null) {
                String type = EngineProtocol.typeOf(line);
                if (EngineProtocol.EXPLAIN_TASK.equals(type)) {
                    ExplainTaskEvent step = ExplainTaskEvent.decode(line);
                    steps.put(Path.of(step.dir()).getFileName() + ":" + step.name(), step);
                }
                if (EngineProtocol.EXPLAIN_DONE.equals(type)) return steps;
                if (EngineProtocol.ERROR.equals(type)) throw new IOException("explain failed: " + line);
            }
        }
        throw new IOException("disconnected before explain-done");
    }

    private static String status(Map<String, ExplainTaskEvent> steps, String module, String step) {
        ExplainTaskEvent found =
                requireNonNull(steps.get(module + ":" + step), module + ":" + step + " in " + steps.keySet());
        return found.status();
    }

    /**
     * The finished journal record of {@code ws}'s latest build. A request's remote uploads land
     * before its record is finished, so after this the remote holds what the build shared.
     */
    static BuildRecord settled(Path ws) throws InterruptedException {
        return settled(ws, 0);
    }

    /** As {@link #settled(Path)}, for a build numbered after {@code after}. */
    private static BuildRecord settled(Path ws, long after) throws InterruptedException {
        BuildRecord[] found = new BuildRecord[1];
        waitUntil(Duration.ofMinutes(3), () -> {
            Optional<BuildRecord> latest = BuildJournal.current().list().stream()
                    .filter(r -> r.dir().equals(ws.toString())
                            && !r.running()
                            && r.kind().equals("build")
                            && r.buildNumber() > after)
                    .findFirst();
            latest.flatMap(r -> BuildJournal.current().get(requireNonNull(r.id(), "id")))
                    .ifPresent(r -> found[0] = r);
            return found[0] != null;
        });
        return found[0];
    }

    /** What the action cache said for every step of {@code record} that consulted it. */
    private static List<StepCache> cacheOf(BuildRecord record) {
        List<StepCache> out = new ArrayList<>();
        for (BuildRecord.Module m : record.modules()) {
            for (BuildRecord.Task t : m.steps()) {
                if (t.cache() != null) out.add(t.cache());
            }
        }
        for (BuildRecord.Task t : record.steps()) {
            if (t.cache() != null) out.add(t.cache());
        }
        return out;
    }

    /** The records under {@code remote} whose {@code KIND} is {@code kind}. */
    private static List<Path> recordsOfKind(Path remote, String kind) throws IOException {
        Path keys = ActionTree.KEYS.under(CacheTree.ACTIONS.under(remote));
        if (!Files.isDirectory(keys)) return List.of();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(keys)) {
            for (Path f : files.toList()) {
                if (Files.readString(f).contains("\nWRITES " + kind + "\n")) out.add(f);
            }
        }
        return out;
    }

    /** Rewrite record {@code file} to say its outputs are for {@code target}. */
    private static void target(Path file, @Nullable String target) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readString(file).split("\n")) {
            if (line.startsWith("TARGET ")) continue;
            out.append(line).append('\n');
            if (line.startsWith("HOST ") && target != null)
                out.append("TARGET ").append(target).append('\n');
        }
        Files.writeString(file, out.toString());
    }

    /** Every task pointer under {@code cache}: the task id and the action key it last stored. */
    private static Map<String, String> taskKeys(Path cache) throws IOException {
        Path tasks = ActionTree.TASKS.under(CacheTree.ACTIONS.under(cache));
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(tasks)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".gens")) continue;
                out.put(name, Files.readString(f).trim());
            }
        }
        return out;
    }
}
