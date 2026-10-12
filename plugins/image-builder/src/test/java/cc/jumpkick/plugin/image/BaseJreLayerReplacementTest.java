// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An upper layer may put a directory where a lower layer had a file, or a file where it had a
 * directory. Either replaces the lower layer's entry; what the layer itself wrote at the path
 * stays, as with a whiteout.
 */
class BaseJreLayerReplacementTest {

    @Test
    void an_upper_layers_directory_replaces_a_lower_layers_file(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path lower = tar(tmp.resolve("lower.tar"), "usr/lib/jvm", "opt/tool", "etc/keep");
        Path upper = tar(tmp.resolve("upper.tar"), "usr/lib/jvm/", "usr/lib/jvm/bin/java", "opt/tool/bin/run");

        BaseJre.unpack(lower, dest, true);
        BaseJre.unpack(upper, dest, true);

        assertThat(dest.resolve("usr/lib/jvm/bin/java"))
                .as("a directory entry replaces the lower layer's file")
                .isRegularFile();
        assertThat(dest.resolve("opt/tool/bin/run"))
                .as("a file entry with no directory entry ahead of it replaces the lower layer's file on its path")
                .isRegularFile();
        assertThat(dest.resolve("etc/keep")).isRegularFile();
    }

    @Test
    void an_upper_layers_file_replaces_a_lower_layers_directory(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path lower = tar(tmp.resolve("lower.tar"), "usr/lib/jvm/bin/java", "usr/lib/jvm/lib/modules", "etc/keep");
        Path upper = tar(tmp.resolve("upper.tar"), "usr/lib/jvm");

        BaseJre.unpack(lower, dest, true);
        BaseJre.unpack(upper, dest, true);

        assertThat(dest.resolve("usr/lib/jvm")).isRegularFile();
        assertThat(dest.resolve("etc/keep")).isRegularFile();
    }

    @Test
    void a_layers_own_file_is_not_replaced_by_its_own_directory_entry(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path layer = tar(tmp.resolve("layer.tar"), "usr/lib/jvm", "usr/lib/jvm/", "usr/lib/jvm/bin/java", "etc/keep");

        BaseJre.unpack(layer, dest, true);

        assertThat(dest.resolve("usr/lib/jvm"))
                .as("what the layer wrote stays; the entries beneath it are skipped")
                .isRegularFile();
        assertThat(dest.resolve("etc/keep"))
                .as("the rest of the layer still unpacks")
                .isRegularFile();
    }

    /** A tar of the given entries: a name ending in {@code /} is a directory, any other a file holding a byte. */
    private static Path tar(Path file, String... names) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            for (String name : names) {
                TarArchiveEntry entry = new TarArchiveEntry(name);
                if (!name.endsWith("/")) entry.setSize(1);
                tar.putArchiveEntry(entry);
                if (!name.endsWith("/")) tar.write('x');
                tar.closeArchiveEntry();
            }
        }
        return file;
    }
}
