// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A base image's archive is unpacked beside the shared tree and swapped into its place. The layers
 * are read from a scratch directory outside the tree being filled, since an opaque whiteout at a
 * layer's root empties that tree of everything the lower layers left, and nothing of the work is
 * left beside the tree.
 */
class BaseJreInstallTest {

    @Test
    void an_opaque_marker_at_a_layers_root_does_not_reach_the_layers_still_to_unpack(@TempDir Path tmp)
            throws Exception {
        Path extractions = Files.createDirectories(tmp.resolve("base-jre"));
        Path root = extractions.resolve("tree");
        Map<String, byte[]> archive = new LinkedHashMap<>();
        archive.put("manifest.json", "[{\"Layers\":[\"l1.tar\",\"l2.tar\"]}]".getBytes(StandardCharsets.UTF_8));
        archive.put("l1.tar", tar(List.of(".wh..wh..opq", "etc/os-release")));
        archive.put("l2.tar", tar(List.of("usr/lib/jvm/bin/java")));
        Path image = Files.write(tmp.resolve("image.tar"), tarOf(archive));

        BaseJre.install(image, root, "base\nsha256:abc\n");

        assertThat(root.resolve("etc/os-release")).isRegularFile();
        assertThat(root.resolve("usr/lib/jvm/bin/java"))
                .as("the layer after the marker still unpacks")
                .isRegularFile();
        assertThat(Files.readString(root.resolve(".extracted"))).isEqualTo("base\nsha256:abc\n");
        try (var left = Files.list(extractions)) {
            assertThat(left.map(p -> p.getFileName().toString()).toList())
                    .as("no work directory is left beside the tree")
                    .containsExactly("tree");
        }
    }

    @Test
    void a_failed_unpack_leaves_the_previous_tree_and_no_work_directories(@TempDir Path tmp) throws Exception {
        Path extractions = Files.createDirectories(tmp.resolve("base-jre"));
        Path root = Files.createDirectories(extractions.resolve("tree"));
        Files.writeString(root.resolve(".extracted"), "base\nsha256:old\n");
        Map<String, byte[]> archive = new LinkedHashMap<>();
        archive.put("manifest.json", "[{\"Layers\":[\"l1.tar\"]}]".getBytes(StandardCharsets.UTF_8));
        archive.put("l1.tar", new byte[] {0x1f, (byte) 0x8b, 0, 0, 0, 0}); // a gzip header that breaks off
        Path image = Files.write(tmp.resolve("image.tar"), tarOf(archive));

        assertThatThrownBy(() -> BaseJre.install(image, root, "base\nsha256:new\n"))
                .isInstanceOf(IOException.class);

        assertThat(Files.readString(root.resolve(".extracted"))).isEqualTo("base\nsha256:old\n");
        try (var left = Files.list(extractions)) {
            assertThat(left.map(p -> p.getFileName().toString()).toList()).containsExactly("tree");
        }
    }

    /** Every build on the host shares the tree, so it is read under a lock file beside it. */
    @Test
    void the_tree_is_read_under_a_lock_file_beside_it(@TempDir Path cacheRoot) throws Exception {
        String base = "example.invalid/jre@sha256:" + "a".repeat(64);
        Path root = BaseJre.treeOf(base, "linux/amd64", cacheRoot);
        Files.createDirectories(root);
        Files.writeString(root.resolve(".extracted"), base + "\nsha256:cafe\n");

        assertThat(BaseJre.javaBinary(base, "linux/amd64", cacheRoot, RegistryAuth.NONE))
                .as("a tree with no java in it")
                .isNull();
        assertThat(root.resolveSibling(root.getFileName() + ".lock")).isRegularFile();
    }

    /** A tar of regular-file entries with the given names, each holding a byte. */
    private static byte[] tar(List<String> names) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : names) entries.put(name, new byte[] {'x'});
        return tarOf(entries);
    }

    private static byte[] tarOf(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = bytes;
                TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            for (var e : entries.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(e.getKey());
                entry.setSize(e.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(e.getValue());
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }
}
