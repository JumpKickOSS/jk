// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.DeadEndpoint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
