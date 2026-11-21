// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestGateTest {

    @Test
    void a_gated_upload_of_a_file_reaches_the_transport_as_the_file(@TempDir Path tmp) throws Exception {
        Path jar = Files.writeString(tmp.resolve("widget-1.0.0.jar"), "jar bytes");
        AtomicReference<Path> streamed = new AtomicReference<>();
        RepoTransport inner = new RepoTransport() {
            @Override
            public Optional<byte[]> fetch(URI uri, RepoCredential credential) {
                return Optional.empty();
            }

            @Override
            public int put(URI uri, byte[] body, String contentType, RepoCredential credential) throws IOException {
                throw new IOException("the file was read whole instead of streamed");
            }

            @Override
            public int putFile(URI uri, Path file, String contentType, RepoCredential credential) {
                streamed.set(file);
                return 201;
            }
        };

        int status = RequestGate.OPEN
                .over(inner)
                .putFile(
                        URI.create("https://repo.example/widget-1.0.0.jar"),
                        jar,
                        "application/java-archive",
                        RepoCredential.ANONYMOUS);

        assertThat(status).isEqualTo(201);
        assertThat(streamed.get()).isEqualTo(jar);
    }
}
