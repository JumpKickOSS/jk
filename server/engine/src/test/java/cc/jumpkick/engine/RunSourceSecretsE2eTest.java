// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.RequestEnvNames;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.RunSource;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.BuildRequest;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A GitLab CI job's shell holds secrets beside the variables that name its run. Through a real
 * engine: the request carries what the client forwards ({@link RequestEnvNames}), the run's record
 * names the CI run, and no secret's value appears in the record, the journal on disk or the HTTP
 * history answer.
 *
 * <p>A whole build through a real engine is the integration tier's (about 16 s here; the fast tier
 * stops a method at 20 s).
 */
@Tag("integration")
class RunSourceSecretsE2eTest extends EngineServerHarness {

    private static final String CANARY = "canary-3f9a7c1e-do-not-leak";

    @Test
    void ci_secrets_never_reach_the_record_the_journal_or_the_api() throws Exception {
        Path project = shortTempDir();
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                """);
        Path src = project.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example;\npublic class Hello {}\n");
        Files.writeString(project.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                """);

        Map<String, String> job = new LinkedHashMap<>();
        job.put("PATH", String.valueOf(System.getenv("PATH")));
        job.put("CI", "true");
        job.put("GITLAB_CI", "true");
        job.put("CI_PIPELINE_ID", "4711");
        job.put("CI_PIPELINE_URL", "https://gitlab.example/acme/app/-/pipelines/4711");
        job.put("CI_PIPELINE_SOURCE", "merge_request_event");
        job.put("CI_COMMIT_REF_NAME", "main");
        job.put("CI_MERGE_REQUEST_SOURCE_BRANCH_NAME", "feature/x");
        job.put("CI_PROJECT_URL", "https://gitlab.example/acme/app");
        List<String> secrets = List.of(
                "CI_JOB_TOKEN",
                "CI_REGISTRY_PASSWORD",
                "CI_DEPLOY_PASSWORD",
                "CI_DEPLOY_KEY",
                "CI_SECRET_VALUE",
                "CIRCLE_TOKEN",
                "GITHUB_TOKEN");
        for (String name : secrets) job.put(name, CANARY + "-" + name);
        // What the client sends: the names the engine reads for itself.
        Map<String, String> shell = new LinkedHashMap<>();
        job.forEach((name, value) -> {
            if (RequestEnvNames.engineReads(name, false)) shell.put(name, value);
        });
        assertThat(shell.keySet())
                .as("no CI or CircleCI secret is forwarded by the CI-run allow-list")
                .doesNotContain(
                        "CI_JOB_TOKEN",
                        "CI_REGISTRY_PASSWORD",
                        "CI_DEPLOY_PASSWORD",
                        "CI_DEPLOY_KEY",
                        "CI_SECRET_VALUE",
                        "CIRCLE_TOKEN");

        Path cache = shortTempDir();
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(
                p, JkEngineConfig.DEFAULTS, httpOnEphemeralPort(shortTempDir()), JkVersion.VERSION, null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(10), () -> Files.exists(EnginePaths.endpoint(p)) && hasContent(p.http()));
        try {
            // The JDK this test runs on, named by its home as `--jdk <home>` names it, and as a
            // client sends its JAVA_HOME: typed, beside a shell that names none. The build then
            // runs on it whatever the host has installed, with no lookup and nothing to fetch.
            String home = System.getProperty("java.home");
            String request = ProtoSession.withToolchain(build(project, cache), home, null, null, home);
            List<String> lines = runToWorkspaceFinish(p, ProtoSession.withShell(request, shell));
            assertThat(Jsonl.bool(lines.getLast(), "success", false))
                    .as(() -> "the fixture build failed:\n" + errorEvents(lines))
                    .isTrue();
            BuildRecord record = settled(project);
            RunSource source = requireNonNull(record.source(), "source");
            assertThat(source.ciProvider()).isEqualTo("gitlab-ci");
            assertThat(source.ciRunUrl()).isEqualTo("https://gitlab.example/acme/app/-/pipelines/4711");
            assertThat(source.ciEvent()).isEqualTo("merge_request_event");
            assertThat(source.branch()).isEqualTo("feature/x");

            assertThat(String.valueOf(record)).doesNotContain(CANARY);
            assertThat(String.join("\n", lines)).as("the request's stream").doesNotContain(CANARY);
            List<Path> leaks = new ArrayList<>();
            try (Stream<Path> files = Files.walk(BuildJournal.current().buildsRoot())) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String text = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                    if (text.contains(CANARY)) leaks.add(f);
                }
            }
            assertThat(leaks).as("journal files holding a secret").isEmpty();

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
            for (String path : List.of("api/history", "api/history?id=" + requireNonNull(record.id()))) {
                HttpResponse<String> api = http.send(
                        HttpRequest.newBuilder(URI.create(url + path))
                                .header("Authorization", "Bearer " + token)
                                .header("X-Jk-Engine-Epoch", epoch)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(api.statusCode()).as(path + ": " + api.body()).isEqualTo(200);
                assertThat(api.body()).as(path).contains("gitlab-ci").doesNotContain(CANARY);
            }
        } finally {
            stop(server, serverThread);
            LockfileReader.clearCache();
        }
    }

    /**
     * Close the engine's listening socket, then give its thread a bounded join, and never let the
     * wait stand in for what the test found: an interrupt (the method's timeout) or a slow drain is
     * teardown, not a verdict, so it neither replaces a failed canary check nor fails a passing one.
     * The thread is a daemon; one still draining is interrupted and left to end.
     */
    private static void stop(EngineServer server, Thread serverThread) {
        server.close();
        try {
            serverThread.join(TEARDOWN.toMillis());
        } catch (InterruptedException timedOut) {
            Thread.currentThread().interrupt();
        }
        if (serverThread.isAlive()) serverThread.interrupt();
    }

    /** How long teardown waits for the engine thread once its socket is closed. */
    private static final Duration TEARDOWN = Duration.ofSeconds(3);

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

    private static BuildRecord settled(Path project) throws InterruptedException {
        BuildRecord[] found = new BuildRecord[1];
        waitUntil(Duration.ofMinutes(2), () -> {
            Optional<BuildRecord> head = BuildJournal.current().list().stream()
                    .filter(r -> r.dir().equals(project.toString()) && !r.running())
                    .findFirst();
            head.flatMap(r -> BuildJournal.current().get(requireNonNull(r.id(), "id")))
                    .ifPresent(r -> found[0] = r);
            return found[0] != null;
        });
        return found[0];
    }

    /** The stream's error events and its finish line, so a fixture build that fails says why. */
    private static String errorEvents(List<String> lines) {
        return String.join("\n", lines.stream().filter(l -> l.contains("rror")).toList());
    }
}
