// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicWritesTest {

    @Test
    void replace_creates_parents_and_round_trips(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("a/b/c.txt");
        AtomicWrites.replace(target, "hello");
        assertThat(Files.readString(target)).isEqualTo("hello");
        // No temp litter left behind.
        try (var kids = Files.list(target.getParent())) {
            assertThat(kids).containsExactly(target);
        }
    }

    @Test
    void replace_overwrites_an_existing_file(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("c.txt");
        AtomicWrites.replace(target, "old");
        AtomicWrites.replace(target, "new");
        assertThat(Files.readString(target)).isEqualTo("new");
    }

    @Test
    void moveInto_replaces_an_existing_file(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("c.txt");
        Files.writeString(target, "stale");
        Path tmp = dir.resolve("c.txt.tmp");
        Files.writeString(tmp, "fresh");
        AtomicWrites.moveInto(tmp, target);
        assertThat(Files.readString(target)).isEqualTo("fresh");
        assertThat(Files.exists(tmp)).isFalse();
    }

    @Test
    void publishDir_installs_a_tree_into_a_fresh_target(@TempDir Path dir) throws IOException {
        Path staging = Files.createDirectory(dir.resolve(".staging"));
        Files.writeString(staging.resolve("top.txt"), "top");
        Files.createDirectories(staging.resolve("sub"));
        Files.writeString(staging.resolve("sub/nested.txt"), "nested");

        Path target = dir.resolve("published");
        AtomicWrites.publishDir(staging, target);

        assertThat(Files.readString(target.resolve("top.txt"))).isEqualTo("top");
        assertThat(Files.readString(target.resolve("sub/nested.txt"))).isEqualTo("nested");
        assertThat(Files.exists(staging)).isFalse(); // the staging name is gone after the rename
    }

    @Test
    void publishDir_refuses_to_clobber_an_existing_target(@TempDir Path dir) throws IOException {
        Path target = Files.createDirectory(dir.resolve("published"));
        Files.writeString(target.resolve("keep.txt"), "original");

        Path staging = Files.createDirectory(dir.resolve(".staging"));
        Files.writeString(staging.resolve("new.txt"), "intruder");

        // Never replaces: a pre-existing target throws, and its contents are left untouched.
        assertThatIOException().isThrownBy(() -> AtomicWrites.publishDir(staging, target));
        assertThat(Files.readString(target.resolve("keep.txt"))).isEqualTo("original");
        assertThat(Files.exists(target.resolve("new.txt"))).isFalse();
        assertThat(Files.exists(staging)).isTrue(); // staging survives so the caller can discard it
    }

    @Test
    void replace_bytes_round_trips(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("bytes.bin");
        byte[] payload = "raw".getBytes(StandardCharsets.UTF_8);
        AtomicWrites.replace(target, payload);
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
    }
}
