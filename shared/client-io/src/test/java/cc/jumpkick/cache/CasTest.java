// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CasTest {

    @Test
    void put_then_read_round_trip(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);
        Path path = cas.put(payload);

        assertThat(path).exists();
        assertThat(Files.readAllBytes(path)).isEqualTo(payload);
        assertThat(path.startsWith(tempDir.resolve("sha256"))).isTrue();
    }

    @Test
    void putStream_keys_by_content_hash_and_round_trips(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);

        Cas.Stored stored = cas.putStream(new ByteArrayInputStream(payload));

        // Same key and bytes as the buffered put — streaming must not change the hash.
        assertThat(stored.path()).isEqualTo(cas.put(payload));
        assertThat(stored.sha256()).isEqualTo("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
        assertThat(stored.size()).isEqualTo(payload.length);
        assertThat(Files.readAllBytes(stored.path())).isEqualTo(payload);
    }

    @Test
    void putStream_is_idempotent(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        Cas.Stored a = cas.putStream(new ByteArrayInputStream(payload));
        Cas.Stored b = cas.putStream(new ByteArrayInputStream(payload));
        assertThat(a.path()).isEqualTo(b.path());
        assertThat(stagingFiles(tempDir))
                .as("the discarded second write leaves no temp")
                .isEmpty();
    }

    /** Every writer stages under {@code sha256/}, the one tree the temp sweep reads, and none leaves a temp behind. */
    @Test
    void every_writer_stages_under_the_sha256_tree_and_leaves_nothing(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        Path src = Files.write(
                Files.createDirectories(tempDir.resolve("in")).resolve("f"), "file".getBytes(StandardCharsets.UTF_8));

        cas.put("bytes".getBytes(StandardCharsets.UTF_8));
        cas.putStream(new ByteArrayInputStream("stream".getBytes(StandardCharsets.UTF_8)));
        cas.putFile(src, Hashing.sha256Hex(src));

        assertThat(stagingFiles(cas.root())).isEmpty();
        try (Stream<Path> top = Files.list(cas.root())) {
            assertThat(top.map(p -> p.getFileName().toString())).containsExactly("sha256");
        }
    }

    /** Every {@code .put-} name anywhere under {@code root}'s {@code sha256/} tree. */
    private static List<Path> stagingFiles(Path root) throws IOException {
        try (Stream<Path> all = Files.walk(root.resolve("sha256"))) {
            return all.filter(p -> p.getFileName().toString().startsWith(".put-"))
                    .toList();
        }
    }

    @Test
    void path_layout_uses_two_two_then_rest(@TempDir Path tempDir) {
        Cas cas = new Cas(tempDir);
        // 'hello world' SHA-256 = b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
        Path p = cas.pathFor("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
        assertThat(p)
                .isEqualTo(
                        tempDir.resolve("sha256/b9/4d/27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"));
    }

    @Test
    void put_is_idempotent(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        Path a = cas.put(payload);
        Path b = cas.put(payload);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void putFile_never_shares_inode_with_source(@TempDir Path tempDir) throws IOException {
        // Invariant: CAS blobs must not hard-link workspace/target outputs.
        Cas cas = new Cas(tempDir.resolve("cas"));
        Path buildOut = tempDir.resolve("target/classes/Hello.class");
        Files.createDirectories(buildOut.getParent());
        Files.writeString(buildOut, "class-bytes-v1");
        String hex = Hashing.sha256Hex(buildOut);

        Path casBlob = cas.putFile(buildOut, hex);
        assertThat(casBlob).exists();
        assertThat(Files.isSameFile(casBlob, buildOut)).isFalse();

        // In-place rewrite of the build tree must not mutate the CAS blob.
        Files.writeString(buildOut, "class-bytes-MUTATED");
        assertThat(Files.readString(casBlob)).isEqualTo("class-bytes-v1");
    }

    @Test
    void read_detects_corruption(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        byte[] payload = "trust me".getBytes(StandardCharsets.UTF_8);
        Path path = cas.put(payload);
        // Mutate the on-disk blob.
        Files.writeString(path, "tampered");

        // We must look it up by the original hash to trigger verification.
        String originalHex = path.getFileName().toString();
        String expected = requireNonNull(requireNonNull(path.getParent()).getParent())
                        .getFileName()
                        .toString()
                + requireNonNull(path.getParent()).getFileName().toString()
                + originalHex;
        assertThatThrownBy(() -> cas.read(expected))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CAS corruption");
    }

    @Test
    void concurrent_putFile_of_one_blob_lands_once_and_leaves_no_staging_file(@TempDir Path tempDir) throws Exception {
        Cas cas = new Cas(tempDir.resolve("cas"));
        byte[] body = "the same bytes from every writer".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(body);

        int writers = 8;
        List<Path> sources = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            Path src = tempDir.resolve("src" + i + "/out.bin");
            Files.createDirectories(src.getParent());
            Files.write(src, body);
            sources.add(src);
        }

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Path>> results = new ArrayList<>();
        try {
            for (Path src : sources) {
                results.add(pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    return cas.putFile(src, hex);
                }));
            }
            go.countDown();
            for (Future<Path> r : results) {
                assertThat(r.get(30, TimeUnit.SECONDS)).isEqualTo(cas.pathFor(hex));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(cas.pathFor(hex)).hasBinaryContent(body);
        try (Stream<Path> shard = Files.list(cas.pathFor(hex).getParent())) {
            assertThat(shard.map(p -> p.getFileName().toString()))
                    .as("a racing writer must not leave its staging file behind")
                    .containsExactly(hex.substring(4));
        }
    }

    @Test
    void a_staging_file_is_never_mistaken_for_a_blob(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        byte[] body = "content".getBytes(StandardCharsets.UTF_8);
        Path src = Files.write(Files.createDirectories(tempDir.resolve("in")).resolve("f"), body);
        String hex = Hashing.sha256Hex(body);
        cas.putFile(src, hex);

        Path leaked = cas.pathFor(hex).resolveSibling(".put-" + hex + "-1-0.tmp");
        Files.write(leaked, body);

        assertThat(Cas.isBlobPath(leaked)).isFalse();
        assertThat(cas.hashFromPath(leaked)).isEmpty();
        assertThat(Cas.isBlobPath(cas.pathFor(hex))).isTrue();
    }
}
