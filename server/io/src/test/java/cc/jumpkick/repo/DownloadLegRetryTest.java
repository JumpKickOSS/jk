// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.MavenStub;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A body that breaks off after the response began is asked for again, a bounded number of times. */
class DownloadLegRetryTest {

    private static final Coordinate LIB = Coordinate.of("com.example", "lib", "1.0");
    private static final String JAR = MavenStub.path("com.example", "lib", "1.0", ".jar");

    private final RemoteStub remote = new RemoteStub("retry.example.test");

    @BeforeEach
    void seed() {
        RepoGroup.clearProcessFetchCache();
        SessionContext.reset();
        new MavenStub(remote.served).leaf("com.example", "lib", "1.0");
    }

    @Test
    void a_body_cut_short_once_is_downloaded_again(@TempDir Path tmp) throws Exception {
        AtomicInteger cuts = new AtomicInteger(1);
        MavenRepo repo = repo(tmp, cutting(cuts));

        MavenRepo.Fetched fetched = repo.fetchArtifact(LIB, null, () -> false);

        assertThat(fetched.size()).isEqualTo(Objects.requireNonNull(remote.served.get(JAR)).length);
        assertThat(cuts).hasValue(0);
    }

    @Test
    void a_body_that_keeps_breaking_off_fails_after_the_last_attempt(@TempDir Path tmp) {
        AtomicInteger cuts = new AtomicInteger(Integer.MAX_VALUE);
        MavenRepo repo = repo(tmp, cutting(cuts));

        assertThatThrownBy(() -> repo.fetchArtifact(LIB, null, () -> false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("broke off");
        assertThat(Integer.MAX_VALUE - cuts.get()).isEqualTo(DownloadLeg.BODY_ATTEMPTS);
    }

    private MavenRepo repo(Path tmp, RepoTransport transport) {
        return MavenRepo.overTransport(
                "retry",
                URI.create("https://retry.example.test/"),
                transport,
                new Cas(tmp.resolve("cas")),
                RepoCredential.ANONYMOUS,
                null,
                false,
                false,
                false);
    }

    /** {@link #remote}, whose jar body resets halfway while {@code cuts} is above zero. */
    private RepoTransport cutting(AtomicInteger cuts) {
        return new RepoTransport() {
            @Override
            public Optional<byte[]> fetch(URI uri, RepoCredential credential) {
                return remote.fetch(uri, credential);
            }

            @Override
            public Optional<Body> fetchBody(URI uri, RepoCredential credential) {
                return remote.fetch(uri, credential).map(bytes -> {
                    if (!uri.getPath().equals(JAR) || cuts.get() <= 0) {
                        return new Body(new ByteArrayInputStream(bytes), bytes.length);
                    }
                    cuts.decrementAndGet();
                    return new Body(resetAfter(bytes, bytes.length / 2), bytes.length);
                });
            }

            @Override
            public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
                return 405;
            }
        };
    }

    private static InputStream resetAfter(byte[] bytes, int served) {
        return new InputStream() {
            private int pos;

            @Override
            public int read() throws IOException {
                if (pos >= served) throw new IOException("connection reset");
                return bytes[pos++] & 0xff;
            }
        };
    }
}
