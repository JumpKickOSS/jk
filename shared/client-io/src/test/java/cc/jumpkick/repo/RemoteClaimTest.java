// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RemoteClaimTest {

    private static final URI REPO = URI.create("https://repo.example/releases");

    private static RepoTransport answering(Optional<byte[]> body) {
        return new RepoTransport() {
            @Override
            public Optional<byte[]> fetch(URI uri, RepoCredential credential) {
                assertThat(uri.toString())
                        .isEqualTo("https://repo.example/releases/com/example/widget/1.0.0/widget-1.0.0.pom.sha1");
                return body;
            }

            @Override
            public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
                throw new AssertionError("a claim check never writes");
            }
        };
    }

    private static RemoteClaim.Verdict ask(RepoTransport transport, Duration timeout) {
        return RemoteClaim.start(transport, REPO, RepoCredential.ANONYMOUS, "com.example", "widget", "1.0.0", timeout)
                .join();
    }

    @Test
    void a_repository_holding_the_pom_has_claimed_the_version() {
        assertThat(ask(answering(Optional.of(new byte[] {1})), Duration.ofSeconds(5)))
                .isEqualTo(RemoteClaim.Verdict.CLAIMED);
    }

    @Test
    void a_repository_without_it_leaves_the_version_free() {
        assertThat(ask(answering(Optional.empty()), Duration.ofSeconds(5))).isEqualTo(RemoteClaim.Verdict.FREE);
    }

    @Test
    void a_repository_that_cannot_be_reached_gives_no_verdict() {
        RepoTransport down = new RepoTransport() {
            @Override
            public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException {
                throw new IOException("offline");
            }

            @Override
            public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
                return 500;
            }
        };
        assertThat(ask(down, Duration.ofSeconds(5))).isEqualTo(RemoteClaim.Verdict.UNKNOWN);
    }

    @Test
    void a_repository_that_never_answers_is_given_up_on_at_the_timeout() {
        RepoTransport silent = new RepoTransport() {
            @Override
            public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws InterruptedException {
                Thread.sleep(Duration.ofSeconds(30));
                return Optional.empty();
            }

            @Override
            public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
                return 500;
            }
        };
        long start = System.nanoTime();
        assertThat(ask(silent, Duration.ofMillis(200))).isEqualTo(RemoteClaim.Verdict.UNKNOWN);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }
}
