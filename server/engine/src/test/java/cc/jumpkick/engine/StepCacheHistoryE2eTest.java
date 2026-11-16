// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.RunSource;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.run.StepCache;
import cc.jumpkick.task.CachePlatform;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.BuildRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Build history says why a step ran, end to end through a real engine: a build, an edit to one
 * source, a rebuild, and the rebuild's record names that source as the input that moved, in the
 * journal and on the HTTP API the dashboard reads.
 */
class StepCacheHistoryE2eTest extends EngineServerHarness {

    private static final String HELLO = """
            package example;
            public class Hello {
                public static void main(String[] args) {
                    System.out.println("hi");
                }
            }
            """;

    @Test
    void a_rebuild_after_one_edit_names_the_edited_source_in_history_and_the_api() throws Exception {
        Path project = shortTempDir();
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                """);
        Path src = project.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, HELLO);
        // A fresh empty lock stands in for "already locked": nothing to resolve.
        Files.writeString(project.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                """);
        // A checkout on a branch with a remote: the run's record names both, and its host.
        Path git = Files.createDirectories(project.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/feature/history\n");
        Files.writeString(git.resolve("config"), "[remote \"origin\"]\n\turl = git@github.com:Acme/App.git\n");
        Path cache = shortTempDir();
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(
                p, JkEngineConfig.DEFAULTS, httpOnEphemeralPort(shortTempDir()), JkVersion.VERSION, null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(10), () -> Files.exists(EnginePaths.endpoint(p)) && hasContent(p.http()));
        try {
            List<String> first = runToWorkspaceFinish(p, build(project, cache));
            assertThat(Jsonl.bool(first.getLast(), "success", false))
                    .as("first build")
                    .isTrue();
            BuildRecord firstRecord = settled(project, 1);
            assertThat(firstRecord.modules().stream().flatMap(m -> m.steps().stream()))
                    .as("a workspace build's steps keep the labels they set: " + firstRecord.modules())
                    .anyMatch(
                            t -> t.name().equals("compile-java") && !t.detail().isEmpty());
            StepCache cold = compile(firstRecord);
            assertThat(cold.outcome()).isEqualTo(StepCache.Outcome.MISS);
            assertThat(cold.reason()).isEqualTo(StepCache.Reason.NO_RECORD);
            assertThat(cold.bytesOut()).as("the classes it stored").isPositive();

            Files.writeString(src, HELLO.replace("\"hi\"", "\"hello\""));
            List<String> second = runToWorkspaceFinish(p, build(project, cache));
            assertThat(Jsonl.bool(second.getLast(), "success", false))
                    .as("rebuild")
                    .isTrue();
            BuildRecord record = settled(project, 2);
            StepCache edited = compile(record);
            assertThat(edited.outcome()).isEqualTo(StepCache.Outcome.MISS);
            assertThat(edited.reason())
                    .as("the rebuild says an input moved: " + edited)
                    .isEqualTo(StepCache.Reason.INPUTS_CHANGED);
            assertThat(edited.changed()).as("and names it").contains("Hello.java");
            RunSource source = requireNonNull(record.source(), "the run's source");
            assertThat(source.branch()).isEqualTo("feature/history");
            assertThat(source.repository()).isEqualTo("https://github.com/acme/app");
            assertThat(source.host()).isEqualTo(CachePlatform.current());
            assertThat(source.ciProvider())
                    .as("the request's shell names no CI")
                    .isNull();

            String token = Files.readString(p.httpToken()).trim();
            String url = Files.readString(p.http()).trim();
            HttpClient http = HttpClient.newHttpClient();
            String epoch = jsonString(
                    http.send(
                                    HttpRequest.newBuilder(URI.create(url + "api/status"))
                                            .header("Authorization", "Bearer " + token)
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .body(),
                    "engineEpoch");
            HttpResponse<String> api = http.send(
                    HttpRequest.newBuilder(URI.create(url + "api/history?id=" + requireNonNull(record.id())))
                            .header("Authorization", "Bearer " + token)
                            .header("X-Jk-Engine-Epoch", epoch)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(api.statusCode()).as(api.body()).isEqualTo(200);
            assertThat(api.body())
                    .as("the API serves the step's cache outcome")
                    .contains("\"reason\":\"inputs-changed\"")
                    .contains("Hello.java")
                    .contains("\"branch\":\"feature/history\"");
        } finally {
            server.close();
            serverThread.join(10_000);
            LockfileReader.clearCache();
        }
    }

    private static String build(Path project, Path cache) {
        return new BuildRequest(
                        project.toString(),
                        cache.toString(),
                        null,
                        1,
                        null,
                        true,
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

    /** The finished record of build {@code number} of {@code project}. */
    private static BuildRecord settled(Path project, long number) throws InterruptedException {
        BuildRecord[] found = new BuildRecord[1];
        waitUntil(Duration.ofMinutes(2), () -> {
            Optional<BuildRecord> head = BuildJournal.current().list().stream()
                    .filter(r -> r.dir().equals(project.toString()) && r.buildNumber() == number && !r.running())
                    .findFirst();
            head.flatMap(r -> BuildJournal.current().get(requireNonNull(r.id(), "id")))
                    .ifPresent(r -> found[0] = r);
            return found[0] != null;
        });
        return found[0];
    }

    /** What the action cache said for the record's java compile. */
    private static StepCache compile(BuildRecord record) {
        for (BuildRecord.Module m : record.modules()) {
            for (BuildRecord.Task t : m.steps()) {
                if (t.name().equals("compile-java") && t.cache() != null) return t.cache();
            }
        }
        for (BuildRecord.Task t : record.steps()) {
            if (t.name().equals("compile-java") && t.cache() != null) return t.cache();
        }
        throw new AssertionError("no cache outcome on compile-java: " + record.modules() + " " + record.steps());
    }
}
