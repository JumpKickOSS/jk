// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.StepCache;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.task.CachePlatform;
import cc.jumpkick.task.RemoteCacheBinding;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.BuildRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ExplainRequest;
import cc.jumpkick.wire.protocol.ExplainTaskEvent;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // ---- fixture -------------------------------------------------------------------------

    private static Path checkout(Path ws, String test) throws Exception {
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
                """);
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

    /** {@code from}'s sources and lock at {@code to}: another checkout of the same commit. */
    private static Path copy(Path from, Path to) throws IOException {
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

    private static Map<String, String> writing(Path remote) {
        return Map.of(RemoteCacheBinding.DIR, remote.toString(), RemoteCacheBinding.WRITE, "true");
    }

    private static Map<String, String> reading(Path remote) {
        return Map.of(RemoteCacheBinding.DIR, remote.toString());
    }

    /** The request's shell: the few variables a build here needs from this JVM's environment, and {@code extra}. */
    private static String withShell(String request, Map<String, String> extra) {
        Map<String, String> shell = new HashMap<>();
        putIfSet(shell, "PATH", System.getenv("PATH"));
        putIfSet(shell, "HOME", System.getenv("HOME"));
        putIfSet(shell, "JAVA_HOME", System.getenv("JAVA_HOME"));
        shell.putAll(extra);
        return ProtoSession.withShell(request, shell);
    }

    private static void putIfSet(Map<String, String> shell, String name, @Nullable String value) {
        if (value != null) shell.put(name, value);
    }

    private List<String> build(Path ws, Path cache, Map<String, String> env) throws IOException {
        String request = new BuildRequest(
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
        return runToWorkspaceFinish(engine, withShell(request, env));
    }

    private static boolean succeeded(List<String> lines) {
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
            c.sendLine(withShell(request, env));
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
    private static BuildRecord settled(Path ws) throws InterruptedException {
        BuildRecord[] found = new BuildRecord[1];
        waitUntil(Duration.ofMinutes(3), () -> {
            Optional<BuildRecord> latest = BuildJournal.current().list().stream()
                    .filter(r -> r.dir().equals(ws.toString())
                            && !r.running()
                            && r.kind().equals("build"))
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
