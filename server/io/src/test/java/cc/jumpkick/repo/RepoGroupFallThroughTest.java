// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.DeadEndpoint;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One candidate's transport failure is that candidate's problem: the fan-out asks the rest. The
 * dead candidate is a {@link DeadEndpoint} — accepted and dropped, the same shape as a reset from a
 * remote — behind a zero-retry client, so each failure costs one attempt and no backoff.
 */
class RepoGroupFallThroughTest {

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
        RunNotices.clear();
    }

    @AfterEach
    void reset() {
        RunNotices.clear();
    }

    @Test
    void version_discovery_falls_through_a_failed_repo_and_warns_once(@TempDir Path tmp) throws Exception {
        Path good = tmp.resolve("good");
        writeMeta(good, "com.example", "lib", "1.0", "2.0");
        Cas cas = new Cas(tmp.resolve("cas"));

        // The warning is a log line: bind the log to this stream rather than swapping System.err,
        // which the logging backend captured when the first test in this JVM touched it.
        var err = new ByteArrayOutputStream();
        Log.install(
                new PrintStream(err, true, StandardCharsets.UTF_8), System.Logger.Level.INFO, UnaryOperator.identity());
        List<String> versions;
        try (DeadEndpoint dead = DeadEndpoint.open()) {
            RepoGroup group = new RepoGroup(List.of(
                    new MavenRepo("dead", dead.uri(), Http.failFast(), cas),
                    new MavenRepo("good", good.toUri(), new Http(), cas)));
            versions = group.availableVersions(Coordinate.of("com.example", "lib", "0"));
            RepoGroup.clearProcessVersionsCache();
            group.availableVersions(Coordinate.of("com.example", "lib", "0"));
        } finally {
            Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
        }
        assertThat(versions).containsExactlyInAnyOrder("1.0", "2.0");
        String out = err.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("repository dead is unreachable").contains("trying the remaining repositories");
        assertThat(out.indexOf("dead is unreachable"))
                .as("said once per run, not per coordinate")
                .isEqualTo(out.lastIndexOf("dead is unreachable"));
    }

    @Test
    void when_every_candidate_fails_the_failure_is_the_answer_not_an_empty_catalog(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        try (DeadEndpoint dead = DeadEndpoint.open()) {
            RepoGroup group = new RepoGroup(List.of(new MavenRepo("dead", dead.uri(), Http.failFast(), cas)));
            assertThatThrownBy(() -> group.availableVersions(Coordinate.of("com.example", "lib", "0")))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void a_pom_fetch_falls_through_a_failed_repo_silently(@TempDir Path tmp) throws Exception {
        Path good = tmp.resolve("good");
        Path pom = good.resolve("com/example/lib/1.0/lib-1.0.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(
                pom,
                "<project><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version></project>");
        Cas cas = new Cas(tmp.resolve("cas"));

        var err = new ByteArrayOutputStream();
        var original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try (DeadEndpoint dead = DeadEndpoint.open()) {
            RepoGroup group = new RepoGroup(List.of(
                    new MavenRepo("dead", dead.uri(), Http.failFast(), cas),
                    new MavenRepo("good", good.toUri(), new Http(), cas)));
            assertThat(group.tryFetchPom(Coordinate.of("com.example", "lib", "1.0")))
                    .isPresent()
                    .get()
                    .extracting(f -> f.repo().name())
                    .isEqualTo("good");
        } finally {
            System.setErr(original);
        }
        assertThat(err.toString(StandardCharsets.UTF_8))
                .as("materialize stays quiet about the fall-through; the sha check is the guard")
                .doesNotContain("unreachable")
                .doesNotContain("trying the remaining");
    }

    /**
     * The repository that served a coordinate's POM is the one that answers for its artifact. When it
     * says the jar is not there — a relocation stub, a BOM — another remote's reset during the same
     * fan-out is that remote's fault, said once, and the coordinate is judged POM-only rather than
     * the lock failing on the remote that never held it.
     */
    @Test
    void an_artifact_leg_is_settled_by_the_repository_that_holds_the_pom_when_another_remote_fails(@TempDir Path tmp)
            throws Exception {
        Path good = tmp.resolve("good");
        writePom(good, "com.example", "stub", "1.0");
        Cas cas = new Cas(tmp.resolve("cas"));
        Coordinate stub = Coordinate.of("com.example", "stub", "1.0");

        var err = new ByteArrayOutputStream();
        Log.install(
                new PrintStream(err, true, StandardCharsets.UTF_8), System.Logger.Level.INFO, UnaryOperator.identity());
        try (DeadEndpoint dead = DeadEndpoint.open()) {
            RepoGroup group = new RepoGroup(List.of(
                    new MavenRepo("good", good.toUri(), new Http(), cas),
                    new MavenRepo("dead", dead.uri(), Http.failFast(), cas)));
            assertThat(group.tryFetchPom(stub)).isPresent();

            assertThat(group.tryFetchArtifact(stub))
                    .as("good holds the POM and has no jar: POM-only")
                    .isEmpty();
            assertThat(group.tryFetchArtifact(stub, "0".repeat(64))).isEmpty();
        } finally {
            Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
        }
        String out = err.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("repository dead is unreachable").contains("good holds the POM");
        assertThat(out.indexOf("dead is unreachable")).isEqualTo(out.lastIndexOf("dead is unreachable"));
    }

    @Test
    void when_no_repository_is_known_to_hold_the_pom_a_remotes_failure_is_still_the_answer(@TempDir Path tmp)
            throws Exception {
        Path good = tmp.resolve("good");
        writePom(good, "com.example", "stub", "1.0");
        Cas cas = new Cas(tmp.resolve("cas"));
        Coordinate stub = Coordinate.of("com.example", "stub", "1.0");
        try (DeadEndpoint dead = DeadEndpoint.open()) {
            RepoGroup group = new RepoGroup(List.of(
                    new MavenRepo("good", good.toUri(), new Http(), cas),
                    new MavenRepo("dead", dead.uri(), Http.failFast(), cas)));
            // No POM was asked through this group: the jar may well live on the remote that failed.
            assertThatThrownBy(() -> group.tryFetchArtifact(stub)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void the_holding_repositorys_own_failure_still_fails_the_leg(@TempDir Path tmp) throws Exception {
        byte[] pom = ("<project><groupId>com.example</groupId><artifactId>stub</artifactId>"
                        + "<version>1.0</version></project>")
                .getBytes(StandardCharsets.UTF_8);
        // Serves the POM and its checksum, and answers 500 for everything else — the jar included.
        HttpServer flaky = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        flaky.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] body = path.endsWith(".pom")
                    ? pom
                    : path.endsWith(".pom.sha256") ? Hashing.sha256Hex(pom).getBytes(StandardCharsets.UTF_8) : null;
            if (body == null) {
                ex.sendResponseHeaders(500, -1);
            } else {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        flaky.start();
        try {
            Cas cas = new Cas(tmp.resolve("cas"));
            Coordinate stub = Coordinate.of("com.example", "stub", "1.0");
            Files.createDirectories(tmp.resolve("empty"));
            URI base = URI.create("http://127.0.0.1:" + flaky.getAddress().getPort() + "/");
            RepoGroup group = new RepoGroup(List.of(
                    new MavenRepo("flaky", base, Http.failFast(), cas),
                    new MavenRepo("empty", tmp.resolve("empty").toUri(), new Http(), cas)));
            assertThat(group.tryFetchPom(stub)).isPresent();

            // flaky holds the POM and failed on the jar: nobody answered for the coordinate.
            assertThatThrownBy(() -> group.tryFetchArtifact(stub))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("500");
        } finally {
            flaky.stop(0);
        }
    }

    private static void writePom(Path repoDir, String group, String artifact, String version) throws IOException {
        Path pom = repoDir.resolve(group.replace('.', '/'))
                .resolve(artifact)
                .resolve(version)
                .resolve(artifact + "-" + version + ".pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(
                pom,
                "<project><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version
                        + "</version></project>");
    }

    private static void writeMeta(Path repoDir, String group, String artifact, String... versions) throws IOException {
        Path dir = repoDir.resolve(group.replace('.', '/')).resolve(artifact);
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("<metadata><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><versioning><versions>");
        for (String v : versions) sb.append("<version>").append(v).append("</version>");
        sb.append("</versions></versioning></metadata>");
        Files.writeString(dir.resolve("maven-metadata.xml"), sb.toString());
    }
}
