// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.HttpStatusException;
import cc.jumpkick.model.Coordinate;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A repository whose credential a helper printed re-runs the helper once when a request is refused,
 * and sends that request once more; a second refusal is the answer.
 */
class MavenRepoCredentialRefreshTest {

    private static final URI BASE = URI.create("https://corp.example/maven/");

    private static final String POM =
            "<project><groupId>com.acme</groupId><artifactId>lib</artifactId><version>1.0</version></project>";

    /** Serves one POM and its sidecar to a request that carries the accepted bearer token; 401 otherwise. */
    private static final class TokenChecking implements RepoTransport {
        final AtomicReference<String> accepted = new AtomicReference<>();
        final List<String> tokensSeen = Collections.synchronizedList(new ArrayList<>());
        final Map<String, byte[]> served;

        TokenChecking(String accepted) {
            this.accepted.set(accepted);
            byte[] pom = POM.getBytes(StandardCharsets.UTF_8);
            String path = "com/acme/lib/1.0/lib-1.0.pom";
            this.served = Map.of(
                    path, pom, path + ".sha1", Hashing.hashHex("SHA-1", pom).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException {
            String token = credential instanceof RepoCredential.Bearer b ? b.token() : "<none>";
            tokensSeen.add(token);
            if (!token.equals(accepted.get())) throw new HttpStatusException(401, uri);
            String rel = BASE.relativize(uri).getPath();
            return Optional.ofNullable(served.get(rel));
        }

        @Override
        public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
            return 0;
        }
    }

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }

    private static MavenRepo repo(TokenChecking transport, Path tmp, String token) {
        return MavenRepo.overTransport(
                "corp",
                BASE,
                transport,
                new Cas(tmp.resolve("cas")),
                new RepoCredential.Bearer(token),
                null,
                false,
                true,
                false);
    }

    @Test
    void a_refused_helper_credential_is_refreshed_once_and_the_request_is_sent_again(@TempDir Path tmp)
            throws Exception {
        TokenChecking transport = new TokenChecking("token-2");
        AtomicInteger refreshes = new AtomicInteger();
        Supplier<RepoCredential> refresh = () -> {
            refreshes.incrementAndGet();
            return new RepoCredential.Bearer("token-2");
        };
        MavenRepo repo = repo(transport, tmp, "token-1").refreshingCredentialWith(refresh);

        repo.fetchPom(Coordinate.of("com.acme", "lib", "1.0"));

        assertThat(refreshes).hasValue(1);
        assertThat(transport.tokensSeen).first().isEqualTo("token-1");
        assertThat(transport.tokensSeen).last().isEqualTo("token-2");
        assertThat(repo.credential()).isEqualTo(new RepoCredential.Bearer("token-2"));
    }

    @Test
    void a_second_refusal_is_the_answer_and_the_helper_is_not_run_a_third_time(@TempDir Path tmp) {
        TokenChecking transport = new TokenChecking("never-issued");
        AtomicInteger refreshes = new AtomicInteger();
        MavenRepo repo = repo(transport, tmp, "token-1").refreshingCredentialWith(() -> {
            refreshes.incrementAndGet();
            return new RepoCredential.Bearer("token-" + (refreshes.get() + 1));
        });

        assertThatThrownBy(() -> repo.fetchPom(Coordinate.of("com.acme", "lib", "1.0")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("corp refused its credential (HTTP 401")
                .hasMessageContaining("the credential helper's new value was refused too");
        assertThatThrownBy(() -> repo.fetchPom(Coordinate.of("com.acme", "lib", "1.0")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("was refused too");

        assertThat(refreshes).hasValue(1);
    }

    @Test
    void a_credential_without_a_helper_is_never_refreshed(@TempDir Path tmp) {
        TokenChecking transport = new TokenChecking("token-2");
        MavenRepo repo = repo(transport, tmp, "token-1");

        assertThatThrownBy(() -> repo.fetchPom(Coordinate.of("com.acme", "lib", "1.0")))
                .isInstanceOf(IOException.class);

        assertThat(transport.tokensSeen).containsOnly("token-1");
    }

    @Test
    void a_failing_helper_rerun_names_the_repository_and_the_refusal(@TempDir Path tmp) {
        TokenChecking transport = new TokenChecking("token-2");
        MavenRepo repo = repo(transport, tmp, "token-1").refreshingCredentialWith(() -> {
            throw new IllegalStateException("gcloud exited 1");
        });

        assertThatThrownBy(() -> repo.fetchPom(Coordinate.of("com.acme", "lib", "1.0")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("corp refused its credential (HTTP 401")
                .hasMessageContaining("gcloud exited 1");
    }
}
