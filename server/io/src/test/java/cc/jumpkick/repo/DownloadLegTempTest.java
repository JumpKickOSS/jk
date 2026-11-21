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
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A download that fails, however it fails, leaves no temp behind in the store. */
class DownloadLegTempTest {

    private static final Coordinate LIB = Coordinate.of("com.example", "lib", "1.0");
    private static final String JAR = MavenStub.path("com.example", "lib", "1.0", ".jar");

    private final RemoteStub remote = new RemoteStub("temp.example.test");

    @BeforeEach
    void seed() {
        RepoGroup.clearProcessFetchCache();
        SessionContext.reset();
        new MavenStub(remote.served).leaf("com.example", "lib", "1.0");
    }

    @Test
    void a_transport_that_throws_a_runtime_exception_leaves_no_temp(@TempDir Path tmp) throws Exception {
        MavenRepo repo = MavenRepo.overTransport(
                "temps",
                URI.create("https://temp.example.test/"),
                failingJarBody(),
                new Cas(tmp.resolve("cas")),
                RepoCredential.ANONYMOUS,
                null,
                false,
                false,
                false);

        assertThatThrownBy(() -> repo.fetchArtifact(LIB, null, () -> false)).hasMessageContaining("transport broke");
        try (Stream<Path> files = Files.walk(tmp)) {
            assertThat(files.filter(f -> f.getFileName().toString().startsWith(".put-")))
                    .isEmpty();
        }
    }

    /** {@link #remote}, whose jar body throws an unchecked exception halfway. */
    private RepoTransport failingJarBody() {
        return new RepoTransport() {
            @Override
            public Optional<byte[]> fetch(URI uri, RepoCredential credential) {
                return remote.fetch(uri, credential);
            }

            @Override
            public Optional<Body> fetchBody(URI uri, RepoCredential credential) {
                return remote.fetch(uri, credential).map(bytes -> {
                    if (!uri.getPath().equals(JAR)) return new Body(new ByteArrayInputStream(bytes), bytes.length);
                    return new Body(
                            new InputStream() {
                                private int pos;

                                @Override
                                public int read() {
                                    if (pos >= bytes.length / 2) throw new IllegalStateException("transport broke");
                                    return bytes[pos++] & 0xff;
                                }
                            },
                            bytes.length);
                });
            }

            @Override
            public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
                return 405;
            }

            @Override
            public int putFile(URI uri, Path file, String contentType, RepoCredential credential) {
                return 405;
            }
        };
    }
}
