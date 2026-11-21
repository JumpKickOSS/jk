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
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A base image's links survive extraction when they stay inside it: a JDK package reaches its
 * configuration and certificates through them. A link that would lead out of the tree is skipped,
 * and nothing a layer writes later goes through one to land outside.
 */
class BaseJreLinksTest {

    @Test
    void an_absolute_symlink_is_read_against_the_image_root(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path layer = tar(
                tmp.resolve("layer.tar"),
                file("etc/ssl/certs/java/cacerts", "certs"),
                symlink("usr/lib/jvm/jdk/lib/security/cacerts", "/etc/ssl/certs/java/cacerts"));

        BaseJre.unpack(layer, dest, false);

        Path link = dest.resolve("usr/lib/jvm/jdk/lib/security/cacerts");
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readSymbolicLink(link).isAbsolute())
                .as("written relative, so the tree can move")
                .isFalse();
        assertThat(Files.readString(link)).isEqualTo("certs");
    }

    @Test
    void a_relative_symlink_inside_the_tree_is_kept_as_written(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path layer = tar(
                tmp.resolve("layer.tar"),
                file("usr/lib/jvm/jdk/bin/java", "jvm"),
                symlink("usr/bin/java", "../lib/jvm/jdk/bin/java"));

        BaseJre.unpack(layer, dest, false);

        Path link = dest.resolve("usr/bin/java");
        assertThat(Files.readSymbolicLink(link)).isEqualTo(Path.of("../lib/jvm/jdk/bin/java"));
        assertThat(Files.readString(link)).isEqualTo("jvm");
    }

    @Test
    void links_still_resolve_after_the_tree_moves(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("fresh"));
        Path layer = tar(
                tmp.resolve("layer.tar"),
                file("etc/java/java.security", "policy"),
                symlink("usr/lib/jvm/jdk/conf/security/java.security", "/etc/java/java.security"));

        BaseJre.unpack(layer, dest, false);
        Path moved = Files.move(dest, tmp.resolve("root"));

        assertThat(Files.readString(moved.resolve("usr/lib/jvm/jdk/conf/security/java.security")))
                .isEqualTo("policy");
    }

    @Test
    void a_symlink_that_leads_out_of_the_tree_is_skipped(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path layer = tar(
                tmp.resolve("layer.tar"),
                file("a/b/keep", "x"),
                symlink("a/up", "../../.."),
                symlink("a/abs-up", "/etc/../../.."),
                symlink("a/b/sideways", "c/../../../.."),
                symlink("a/self", "."),
                symlink("a/through", "self/b/../.."));

        BaseJre.unpack(layer, dest, false);

        assertThat(dest.resolve("a/up")).doesNotExist();
        assertThat(dest.resolve("a/abs-up")).doesNotExist();
        assertThat(dest.resolve("a/b/sideways")).doesNotExist();
        assertThat(dest.resolve("a/through"))
                .as("a climb after a name may pass through a link and out")
                .doesNotExist();
        assertThat(Files.isSymbolicLink(dest.resolve("a/self"))).isTrue();
        assertThat(dest.resolve("a/b/keep")).isRegularFile();
    }

    @Test
    void a_later_layer_writes_through_a_lower_link_into_the_tree(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path lower = tar(tmp.resolve("lower.tar"), dir("usr/lib/"), symlink("lib", "usr/lib"));
        Path upper = tar(tmp.resolve("upper.tar"), file("lib/libz.so", "z"));

        BaseJre.unpack(lower, dest, true);
        BaseJre.unpack(upper, dest, true);

        assertThat(Files.readString(dest.resolve("usr/lib/libz.so"))).isEqualTo("z");
    }

    @Test
    void an_upper_layers_directory_entry_replaces_a_lower_link(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path lower = tar(tmp.resolve("lower.tar"), file("usr/lib/old.so", "old"), symlink("lib", "usr/lib"));
        Path upper = tar(tmp.resolve("upper.tar"), dir("lib/"), file("lib/new.so", "new"));

        BaseJre.unpack(lower, dest, true);
        BaseJre.unpack(upper, dest, true);

        assertThat(Files.isSymbolicLink(dest.resolve("lib"))).isFalse();
        assertThat(dest.resolve("lib/new.so")).isRegularFile();
        assertThat(dest.resolve("usr/lib/new.so")).doesNotExist();
        assertThat(dest.resolve("usr/lib/old.so")).isRegularFile();
    }

    @Test
    void a_hard_link_becomes_a_copy_of_the_file_it_names(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path layer = tar(
                tmp.resolve("layer.tar"),
                file("usr/lib/jvm/jdk/lib/libjvm.so", "jvm"),
                hardlink("usr/lib/jvm/jdk/lib/server/libjvm.so", "usr/lib/jvm/jdk/lib/libjvm.so"),
                hardlink("usr/lib/escape", "../outside"));

        BaseJre.unpack(layer, dest, false);

        assertThat(Files.readString(dest.resolve("usr/lib/jvm/jdk/lib/server/libjvm.so")))
                .isEqualTo("jvm");
        assertThat(dest.resolve("usr/lib/escape")).doesNotExist();
    }

    @Test
    void an_upper_layers_file_replaces_a_lower_layers_link(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path lower = tar(tmp.resolve("lower.tar"), file("etc/real", "lower"), symlink("etc/alias", "real"));
        Path upper = tar(tmp.resolve("upper.tar"), file("etc/alias", "upper"));

        BaseJre.unpack(lower, dest, true);
        BaseJre.unpack(upper, dest, true);

        assertThat(Files.isSymbolicLink(dest.resolve("etc/alias"))).isFalse();
        assertThat(Files.readString(dest.resolve("etc/alias"))).isEqualTo("upper");
        assertThat(Files.readString(dest.resolve("etc/real"))).isEqualTo("lower");
    }

    private record Entry(String name, byte type, String content) {}

    private static Entry file(String name, String content) {
        return new Entry(name, TarConstants.LF_NORMAL, content);
    }

    private static Entry dir(String name) {
        return new Entry(name, TarConstants.LF_DIR, "");
    }

    private static Entry symlink(String name, String linkTarget) {
        return new Entry(name, TarConstants.LF_SYMLINK, linkTarget);
    }

    private static Entry hardlink(String name, String linkTarget) {
        return new Entry(name, TarConstants.LF_LINK, linkTarget);
    }

    private static Path tar(Path file, Entry... entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            for (Entry e : entries) {
                TarArchiveEntry entry = new TarArchiveEntry(e.name(), e.type());
                if (e.type() == TarConstants.LF_SYMLINK || e.type() == TarConstants.LF_LINK) {
                    entry.setLinkName(e.content());
                    tar.putArchiveEntry(entry);
                } else if (e.type() == TarConstants.LF_DIR) {
                    tar.putArchiveEntry(entry);
                } else {
                    byte[] bytes = e.content().getBytes(StandardCharsets.UTF_8);
                    entry.setSize(bytes.length);
                    tar.putArchiveEntry(entry);
                    tar.write(bytes);
                }
                tar.closeArchiveEntry();
            }
        }
        return file;
    }
}
