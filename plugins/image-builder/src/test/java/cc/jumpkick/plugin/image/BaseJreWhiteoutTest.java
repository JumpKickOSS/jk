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
 * {@code .wh..} names the directory itself. The opaque marker {@code .wh..wh..opq} is honoured
 * with its OCI meaning — the directory it sits in is emptied of the lower layers — and, like
 * every whiteout, never reaches what its own layer wrote.
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

    /**
     * {@code .wh..wh..opq} is the opaque whiteout: the directory it sits in starts empty at this
     * layer. At the layer root that directory is the extraction directory itself, which stays —
     * emptied of the lower layers, then filled by this layer — and never a sibling extraction.
     */
    @Test
    void an_opaque_marker_at_the_layer_root_clears_the_lower_layers_and_keeps_the_extraction_directory(
            @TempDir Path tmp) throws Exception {
        Path extractions = Files.createDirectories(tmp.resolve("base-jre"));
        Path other = write(extractions.resolve("other-image/bin/java"), "another image's JRE");
        Path dest = Files.createDirectories(extractions.resolve("this-image"));
        Path lowerFile = write(dest.resolve("etc/os-release"), "from a lower layer");
        Path lowerTree = write(dest.resolve("usr/lib/jvm/bin/java"), "from a lower layer")
                .getParent();
        Path layer = tar(tmp.resolve("layer.tar"), ".wh..wh..opq", "bin/java");

        BaseJre.unpack(layer, dest, true);

        assertThat(dest).isDirectory();
        assertThat(lowerFile).doesNotExist();
        assertThat(lowerTree).doesNotExist();
        assertThat(dest.resolve("bin/java"))
                .as("the layer's own content lands in the emptied directory")
                .isRegularFile();
        assertThat(other)
                .as("a sibling extraction is outside this layer's reach")
                .isRegularFile();
    }

    @Test
    void an_opaque_marker_inside_a_directory_clears_only_that_directory(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path lowerJar = write(dest.resolve("lib/legacy/a.jar"), "lower");
        Path lowerFile = write(dest.resolve("lib/notes.txt"), "lower");
        Path outside = write(dest.resolve("etc/os-release"), "lower");
        Path layer = tar(tmp.resolve("layer.tar"), "lib/.wh..wh..opq", "lib/current/b.jar");

        BaseJre.unpack(layer, dest, true);

        assertThat(lowerJar).doesNotExist();
        assertThat(lowerJar.getParent()).doesNotExist();
        assertThat(lowerFile).doesNotExist();
        assertThat(dest.resolve("lib")).isDirectory();
        assertThat(dest.resolve("lib/current/b.jar")).isRegularFile();
        assertThat(outside)
                .as("a directory the marker does not sit in is untouched")
                .isRegularFile();
    }

    /**
     * A whiteout only reaches the lower layers: what this layer has already written stays, even
     * when the layer lists its content ahead of the marker.
     */
    @Test
    void an_opaque_marker_leaves_what_its_own_layer_wrote_before_it(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path lower = write(dest.resolve("lib/old.jar"), "lower");
        Path layer = tar(tmp.resolve("layer.tar"), "lib/own/b.jar", "lib/own.jar", "lib/.wh..wh..opq");

        BaseJre.unpack(layer, dest, true);

        assertThat(lower).doesNotExist();
        assertThat(dest.resolve("lib/own.jar")).isRegularFile();
        assertThat(dest.resolve("lib/own/b.jar")).isRegularFile();
    }

    /**
     * A directory the layer wrote into is not the layer's as a whole: the marker still clears the
     * lower layers' files inside it and leaves the layer's own.
     */
    @Test
    void an_opaque_marker_clears_lower_files_inside_a_directory_its_own_layer_wrote_into(@TempDir Path tmp)
            throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path lowerInside = write(dest.resolve("lib/own/old.jar"), "lower");
        Path layer = tar(tmp.resolve("layer.tar"), "lib/own/b.jar", "lib/.wh..wh..opq");

        BaseJre.unpack(layer, dest, true);

        assertThat(lowerInside).doesNotExist();
        assertThat(dest.resolve("lib/own/b.jar")).isRegularFile();
    }

    @Test
    void a_whiteout_for_a_directory_its_own_layer_wrote_into_clears_only_the_lower_files(@TempDir Path tmp)
            throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path lowerInside = write(dest.resolve("lib/own/old.jar"), "lower");
        Path layer = tar(tmp.resolve("layer.tar"), "lib/own/b.jar", "lib/.wh.own");

        BaseJre.unpack(layer, dest, true);

        assertThat(lowerInside).doesNotExist();
        assertThat(dest.resolve("lib/own/b.jar")).isRegularFile();
    }

    @Test
    void a_whiteout_leaves_a_file_its_own_layer_wrote_before_it(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path layer = tar(tmp.resolve("layer.tar"), "keep.txt", ".wh.keep.txt");

        BaseJre.unpack(layer, dest, true);

        assertThat(dest.resolve("keep.txt")).isRegularFile();
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
