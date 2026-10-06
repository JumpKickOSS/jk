// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTransportTest {

    private final FileTransport transport = new FileTransport();

    @Test
    void fetch_reads_an_existing_file(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("repo/g/a/1/a-1.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "bytes");

        Optional<byte[]> body = transport.fetch(jar.toUri(), RepoCredential.ANONYMOUS);
        assertThat(body).isPresent();
        assertThat(new String(body.get(), StandardCharsets.UTF_8)).isEqualTo("bytes");
    }

    @Test
    void fetch_missing_file_is_empty(@TempDir Path dir) throws Exception {
        assertThat(transport.fetch(dir.resolve("nope.jar").toUri(), RepoCredential.ANONYMOUS))
                .isEmpty();
    }

    @Test
    void put_writes_creating_parent_dirs(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("repo/g/a/1/a-1.jar");
        int status = transport.put(
                target.toUri(), new byte[] {1, 2, 3}, "application/java-archive", RepoCredential.ANONYMOUS);
        assertThat(status).isEqualTo(201);
        assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3);
    }

    /** A put over a hard-linked file replaces this name only; the other name keeps its bytes. */
    @Test
    void put_over_a_hard_linked_file_leaves_the_other_name_unchanged(@TempDir Path dir) throws Exception {
        Path m2 = dir.resolve("m2/g/a/1/a-1.pom");
        Files.createDirectories(m2.getParent());
        Files.writeString(m2, "<project/>");
        Path target = dir.resolve("repo/g/a/1/a-1.pom");
        Files.createDirectories(target.getParent());
        Files.createLink(target, m2);

        transport.put(
                target.toUri(), "replaced".getBytes(StandardCharsets.UTF_8), "text/xml", RepoCredential.ANONYMOUS);

        assertThat(Files.readString(target)).isEqualTo("replaced");
        assertThat(Files.readString(m2)).isEqualTo("<project/>");
    }

    @Test
    void dispatches_for_file_scheme(@TempDir Path dir) {
        assertThat(RepoTransports.forUrl(dir.toUri(), new Http())).isInstanceOf(FileTransport.class);
    }
}
