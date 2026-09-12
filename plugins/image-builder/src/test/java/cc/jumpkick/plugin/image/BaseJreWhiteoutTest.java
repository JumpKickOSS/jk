// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A base image's layers come from a registry, and a whiteout entry names what to delete. The
 * deletion must land inside the directory the layer is being unpacked into: {@code .wh...} names
 * the parent of that directory, which is every cached JRE extraction on the machine, and
 * {@code .wh..} names the directory itself.
 */
class BaseJreWhiteoutTest {

    @Test
    void a_whiteout_naming_the_extraction_directory_or_its_parents_is_refused(@TempDir Path tmp) throws Exception {
        Path extractions = Files.createDirectories(tmp.resolve("base-jre"));
        Path other = write(extractions.resolve("other-image/bin/java"), "another image's JRE");
        Path dest = Files.createDirectories(extractions.resolve("this-image"));
        Path existing = write(dest.resolve("etc/os-release"), "from a lower layer");
        Path layer = tar(tmp.resolve("layer.tar"), ".wh...", ".wh..", ".wh.", "sub/.wh...", "sub/.wh..", "keep.txt");

        BaseJre.unpack(layer, dest, true);

        assertThat(other)
                .as("a sibling extraction is outside this layer's reach")
                .isRegularFile();
        assertThat(existing)
                .as("the extraction directory itself is not a whiteout's target")
                .isRegularFile();
        assertThat(dest.resolve("keep.txt"))
                .as("the rest of the layer still unpacks")
                .isRegularFile();
    }

    @Test
    void a_whiteout_removes_the_named_file_from_the_lower_layers(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path removed = write(dest.resolve("old.txt"), "lower");
        Path removedDir = write(dest.resolve("lib/legacy/a.jar"), "lower").getParent();
        Path kept = write(dest.resolve("lib/current/b.jar"), "lower");
        Path layer = tar(tmp.resolve("layer.tar"), ".wh.old.txt", "lib/.wh.legacy");

        BaseJre.unpack(layer, dest, true);

        assertThat(removed).doesNotExist();
        assertThat(removedDir).doesNotExist();
        assertThat(kept).isRegularFile();
    }

    /** A tar of regular-file entries with the given names, each holding a byte. */
    private static Path tar(Path file, String... names) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            for (String name : names) {
                TarArchiveEntry entry = new TarArchiveEntry(name);
                entry.setSize(1);
                tar.putArchiveEntry(entry);
                tar.write('x');
                tar.closeArchiveEntry();
            }
        }
        return file;
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
