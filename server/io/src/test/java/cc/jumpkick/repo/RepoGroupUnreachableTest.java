// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.RunNotices;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
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
 * more for the rest of the job. Pinned bytes still fall through — the sha256 says what is accepted.
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

    /**
     * The JDK's HTTP client reports a refused connect as a {@code ConnectException} with no message,
     * wrapped in another and a {@code ClosedChannelException}; a peer that accepted and dropped the
     * connection is the same class saying reset, and a reset is one request's failure, not a dead
     * address.
     */
    @Test
    void a_refused_connect_is_the_message_less_connect_exception_and_a_reset_is_not_one() {
        ConnectException bare = new ConnectException();
        bare.initCause(new ClosedChannelException());
        IOException refused = new IOException("GET failed after 6 attempts", bare);
        assertThat(MavenRepo.connectFailure(refused)).isEqualTo("ConnectException: the connection was not accepted");
        assertThat(MavenRepo.connectFailure(new ConnectException("Connection refused")))
                .isEqualTo("ConnectException: Connection refused");
        assertThat(MavenRepo.connectFailure(new ConnectException("Connection reset by peer (connect failed)")))
                .isNull();
        assertThat(MavenRepo.connectFailure(new IOException("HTTP 503"))).isNull();
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
