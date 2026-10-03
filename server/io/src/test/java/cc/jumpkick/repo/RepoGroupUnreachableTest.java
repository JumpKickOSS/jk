// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.ConnectFaults;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.HttpStatusException;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.DeadEndpoint;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A repository nothing answers at — the connection is refused, the host unknown, the connect times
 * out — is not a remote that dropped one request. On the resolve leg the next candidate is not
 * asked: a lock computed without a configured repository is not the lock that was asked for, so the
 * resolve stops at once naming the repository and its URL, and the repository is asked nothing
 * more for the rest of the job — unless it is passable (declared {@code optional}, at a loopback
 * address, or a POM's), which is passed over as a blocked one is. Pinned bytes still fall through
 * — the sha256 says what is accepted.
 */
class RepoGroupUnreachableTest {

    private static final URI DEAD = URI.create("http://dead.invalid/maven2/");

    /** Every request refused, the way a host with nothing listening refuses; the URIs asked are kept. */
    private static final class Refusing implements RepoTransport {
        final List<URI> asked = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException {
            asked.add(uri);
            throw new ConnectException("Connection refused");
        }

        @Override
        public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
            return 0;
        }
    }

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

    private static MavenRepo dead(Refusing transport, Cas cas) {
        return MavenRepo.overTransport(
                "dead", DEAD, transport, cas, RepoCredential.ANONYMOUS, null, false, false, false);
    }

    private static MavenRepo good(Path tmp, Cas cas) throws IOException {
        Path good = tmp.resolve("good");
        Path pom = good.resolve("com/example/lib/1.0/lib-1.0.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(
                pom,
                "<project><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version></project>");
        Files.writeString(pom.resolveSibling("lib-1.0.jar"), "jar bytes");
        Files.writeString(
                good.resolve("com/example/lib/maven-metadata.xml"),
                "<metadata><groupId>com.example</groupId><artifactId>lib</artifactId><versioning><versions>"
                        + "<version>1.0</version></versions></versioning></metadata>");
        return new MavenRepo("good", good.toUri(), new Http(), cas);
    }

    @Test
    void a_pom_fetch_from_a_repository_that_refuses_the_connection_stops_the_resolve_naming_it(@TempDir Path tmp)
            throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        Refusing refusing = new Refusing();
        RepoGroup group = new RepoGroup(List.of(dead(refusing, cas), good(tmp, cas)));

        assertThatThrownBy(() -> group.tryFetchPom(Coordinate.of("com.example", "lib", "1.0")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("dead")
                .hasMessageContaining(DEAD.toString())
                .hasMessageContaining("Connection refused")
                .hasMessageContaining("[repositories]");

        assertThat(refusing.asked).as("the refusal was measured").isNotEmpty();
        // The first coordinate's checksum reads may still be landing on pool threads, so the second
        // coordinate is judged by its own URIs: none is ever dialled.
        assertThatThrownBy(() -> group.tryFetchPom(Coordinate.of("com.example", "other", "2.0")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(DEAD.toString());
        assertThat(refusing.asked)
                .as("a repository found unreachable is asked nothing more")
                .noneMatch(uri -> uri.getPath().contains("/other/"));
    }

    @Test
    void a_version_catalog_read_from_a_repository_that_refuses_the_connection_stops_the_resolve_too(@TempDir Path tmp)
            throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup group = new RepoGroup(List.of(dead(new Refusing(), cas), good(tmp, cas)));

        assertThatThrownBy(
                        () -> group.availableVersions(Coordinate.of("com.example", "lib", "0"), Set.of("1.0"), false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(DEAD.toString());
    }

    /**
     * The address, not the repository object, is what answered nothing: a second {@link MavenRepo}
     * over the same URL — the sync leg builds one per lock source, a declared-repository group one
     * per subtree — is refused before it dials, and its cause says so.
     */
    @Test
    void a_fresh_repository_object_over_an_address_that_answered_nothing_is_refused_without_a_request(@TempDir Path tmp)
            throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        URI closed = URI.create("http://127.0.0.1:" + port + "/maven2/");
        Coordinate coord = Coordinate.of("com.example", "lib", "1.0");

        // The POM and its checksum sidecars are asked for together, so whichever request's ladder
        // ends first pays it and the rest are refused; either way the repository is named.
        assertThatThrownBy(() -> new MavenRepo("dead", closed, Http.failFast(), cas).fetchPom(coord))
                .isInstanceOf(MavenRepo.RepositoryUnreachableException.class)
                .hasMessageContaining("repository dead")
                .hasMessageContaining("is unreachable");

        assertThatThrownBy(() -> new MavenRepo("dead", closed, Http.failFast(), cas).fetchArtifact(coord))
                .isInstanceOf(MavenRepo.RepositoryUnreachableException.class)
                .hasMessageContaining("repository dead")
                .cause()
                .hasMessageContaining("was not attempted")
                .hasCauseInstanceOf(ConnectFaults.Remembered.class);
    }

    /**
     * An {@code optional} repository — what {@code jk import} writes for a POM's — that answers
     * nothing is passed over like a blocked one: the resolve goes on to the next candidate, the
     * repository is asked nothing more, and the group names it for a package that resolves nowhere.
     */
    @Test
    void an_optional_repository_that_refuses_the_connection_is_passed_over(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        Refusing refusing = new Refusing();
        MavenRepo dead = dead(refusing, cas).withOptional(true);
        RepoGroup group = new RepoGroup(List.of(dead, good(tmp, cas)));

        assertThat(dead.passable()).isTrue();
        assertThat(group.tryFetchPom(Coordinate.of("com.example", "lib", "1.0")))
                .get()
                .extracting(f -> f.repo().name())
                .isEqualTo("good");
        assertThat(group.availableVersions(Coordinate.of("com.example", "lib", "0"), Set.of("1.0"), false))
                .containsExactly("1.0");
        assertThat(group.tryFetchPom(Coordinate.of("com.example", "other", "2.0")))
                .as("a package nothing serves is a miss, not the unreachable repository's failure")
                .isEmpty();
        assertThat(refusing.asked)
                .as("a repository found unreachable is asked nothing more")
                .noneMatch(uri -> uri.getPath().contains("/other/"));
        assertThat(group.passedOver()).containsExactly(dead);
        assertThat(dead.unreachableFault()).contains("Connection refused");
    }

    /** A loopback repository is a developer's local Nexus: passable without saying so, like an optional one. */
    @Test
    void a_loopback_repository_that_refuses_the_connection_is_passed_over(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo nexus = MavenRepo.overTransport(
                "nexus",
                URI.create("http://localhost:8081/repository/maven-releases/"),
                new Refusing(),
                cas,
                RepoCredential.ANONYMOUS,
                null,
                false,
                false,
                false);
        RepoGroup group = new RepoGroup(List.of(nexus, good(tmp, cas)));

        assertThat(nexus.passable()).isTrue();
        assertThat(dead(new Refusing(), cas).passable())
                .as("a remote repository the user wrote by hand keeps the stop")
                .isFalse();
        assertThat(group.tryFetchPom(Coordinate.of("com.example", "lib", "1.0")))
                .get()
                .extracting(f -> f.repo().name())
                .isEqualTo("good");
        assertThat(group.passedOver()).containsExactly(nexus);
    }

    /**
     * A connection dropped before any response is no answer either: a passable repository over it
     * is passed over after one attempt, since its client does not retry silence.
     */
    @Test
    void a_passable_repository_whose_connection_is_dropped_is_passed_over_after_one_attempt(@TempDir Path tmp)
            throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        try (DeadEndpoint dead = DeadEndpoint.open()) {
            MavenRepo nexus = new MavenRepo("nexus", dead.uri("/maven2/"), new Http().withoutSilenceRetries(), cas);
            RepoGroup group = new RepoGroup(List.of(nexus, good(tmp, cas)));

            assertThatThrownBy(() -> nexus.fetchPom(Coordinate.of("com.example", "lib", "1.0")))
                    .isInstanceOf(MavenRepo.RepositoryUnreachableException.class)
                    .as("one attempt, not the retry ladder")
                    .hasStackTraceContaining("failed after 1 attempts");
            assertThat(group.tryFetchPom(Coordinate.of("com.example", "lib", "1.0")))
                    .get()
                    .extracting(f -> f.repo().name())
                    .isEqualTo("good");
            assertThat(group.passedOver()).containsExactly(nexus);
        }
    }

    /** Every request answered with {@code status}, the way an Artifactory that wants a login answers. */
    private record Denying(int status) implements RepoTransport {
        @Override
        public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException {
            throw new HttpStatusException(status, uri);
        }

        @Override
        public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
            return 0;
        }
    }

    /**
     * tutorials' shape: a POM's repository answers 401 for a coordinate no repository has. A
     * passable repository that refuses access is passed over like one nothing answers at — the
     * package is a miss, and the group names the repository with the status it met.
     */
    @Test
    void an_optional_repository_that_refuses_access_is_passed_over(@TempDir Path tmp) throws Exception {
        for (int status : new int[] {401, 403}) {
            clear();
            Cas cas = new Cas(tmp.resolve("cas-" + status));
            MavenRepo denying = MavenRepo.overTransport(
                            "vendor",
                            DEAD,
                            new Denying(status),
                            cas,
                            RepoCredential.ANONYMOUS,
                            null,
                            false,
                            false,
                            false)
                    .withOptional(true);
            RepoGroup group = new RepoGroup(List.of(denying, good(tmp, cas)));

            assertThat(group.tryFetchPom(Coordinate.of("com.example", "other", "2.0")))
                    .as("HTTP %d from a passable repository leaves a miss, not a failure", status)
                    .isEmpty();
            assertThat(group.tryFetchPom(Coordinate.of("com.example", "lib", "1.0")))
                    .get()
                    .extracting(f -> f.repo().name())
                    .isEqualTo("good");
            assertThat(group.availableVersions(Coordinate.of("com.example", "lib", "0"), Set.of("1.0"), false))
                    .containsExactly("1.0");
            assertThat(group.passedOver()).containsExactly(denying);
            assertThat(denying.unreachableFault()).startsWith("HTTP " + status);
        }
    }

    /** A repository written by hand that answers 401 is a credentials problem the user must fix: the resolve stops on it. */
    @Test
    void a_hand_written_repository_that_refuses_access_stops_the_resolve(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo denying = MavenRepo.overTransport(
                "vendor", DEAD, new Denying(401), cas, RepoCredential.ANONYMOUS, null, false, false, false);
        RepoGroup group = new RepoGroup(List.of(denying, good(tmp, cas)));

        assertThat(denying.passable()).isFalse();
        assertThatThrownBy(() -> group.tryFetchPom(Coordinate.of("com.example", "other", "2.0")))
                .isInstanceOf(HttpStatusException.class)
                .hasMessageContaining("HTTP 401");
        assertThat(group.passedOver()).isEmpty();
    }

    /** A repository a dependency's POM declares is held to Maven's rule: passed over when nothing answers there. */
    @Test
    void a_repository_a_pom_declares_is_passable(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo declared =
                good(tmp, cas).declaredByPom("vendor", URI.create("https://vendor.example/maven2/"), true, true);

        assertThat(declared.passable()).isTrue();
    }

    @Test
    void pinned_bytes_still_fall_through_a_repository_that_refuses_the_connection(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup group = new RepoGroup(List.of(dead(new Refusing(), cas), good(tmp, cas)));

        assertThat(group.tryFetchArtifact(Coordinate.of("com.example", "lib", "1.0")))
                .isPresent()
                .get()
                .extracting(f -> f.repo().name())
                .isEqualTo("good");
    }
}
