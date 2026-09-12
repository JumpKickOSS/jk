// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyFilesTest {

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void a_fresh_file_is_owner_only_with_its_content(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("creds");
        Path file = dir.resolve("token");

        OwnerOnlyFiles.write(dir, file, "secret");

        assertThat(Files.readString(file)).isEqualTo("secret");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    /**
     * A secret is never written into a file another user can read. An existing loose file is not
     * rewritten in place: a second name for that inode (a hard link) would carry the new secret at
     * the old mode. The alias standing in for the loose inode keeps the stale bytes.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void an_existing_loose_file_never_carries_the_secret(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("creds");
        Files.createDirectories(dir);
        Path file = Files.writeString(dir.resolve("token"), "stale");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        Path alias = Files.createLink(tmp.resolve("alias"), file);

        OwnerOnlyFiles.write(dir, file, "secret");

        assertThat(Files.readString(file)).isEqualTo("secret");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readString(alias))
                .as("the world-readable inode never saw the secret")
                .isEqualTo("stale");
    }
}
