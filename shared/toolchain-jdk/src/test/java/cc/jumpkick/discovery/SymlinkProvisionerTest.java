// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import cc.jumpkick.util.JkOwnership;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS) // symlink creation requires Developer Mode there
class SymlinkProvisionerTest {

    @Test
    void can_symlink_returns_true_on_posix() {
        assertThat(SymlinkProvisioner.canSymlink()).isTrue();
    }

    @Test
    void link_creates_a_symlink_to_target(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("source");
        Path link = tempDir.resolve("link");
        Files.createDirectories(source);

        SymlinkProvisioner.link(link, source);
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readSymbolicLink(link)).isEqualTo(source);
    }

    @Test
    void link_refuses_to_clobber_a_populated_directory_jk_did_not_create(@TempDir Path tempDir) throws IOException {
        // . link cleared the way by recursing into whatever sat at the target. Every
        // current caller happens to pass a path under jk's own store, so this never fired — but the
        // class advertises itself as shared with the JDK side, and that is exactly the caller that
        // pointed the identical logic at ~/.jdks and destroyed real installs.
        Path theirs = tempDir.resolve("their-tool");
        Files.createDirectories(theirs);
        Files.writeString(theirs.resolve("payload.txt"), "irreplaceable\n");
        Path source = Files.createDirectories(tempDir.resolve("source"));

        assertThatIOException()
                .isThrownBy(() -> SymlinkProvisioner.link(theirs, source))
                .withMessageContaining("jk did not create it");

        assertThat(theirs.resolve("payload.txt")).exists();
        assertThat(Files.isSymbolicLink(theirs)).isFalse();
    }

    @Test
    void link_still_replaces_a_populated_directory_jk_did_create(@TempDir Path tempDir) throws IOException {
        // The flip side: a stale download of jk's own is still cleared, or the refusal above would
        // turn provisioning into a dead end.
        Path ours = tempDir.resolve("our-tool");
        Files.createDirectories(ours);
        Files.writeString(ours.resolve("payload.txt"), "stale\n");
        JkOwnership.mark(ours);
        Path source = Files.createDirectories(tempDir.resolve("source"));

        SymlinkProvisioner.link(ours, source);

        assertThat(Files.isSymbolicLink(ours)).isTrue();
        assertThat(Files.readSymbolicLink(ours)).isEqualTo(source);
    }

    @Test
    void link_replaces_existing_entry(@TempDir Path tempDir) throws IOException {
        Path link = tempDir.resolve("link");
        Path source1 = tempDir.resolve("source1");
        Path source2 = tempDir.resolve("source2");
        Files.createDirectories(source1);
        Files.createDirectories(source2);

        SymlinkProvisioner.link(link, source1);
        SymlinkProvisioner.link(link, source2);

        assertThat(Files.readSymbolicLink(link)).isEqualTo(source2);
    }

    @Test
    void is_broken_link_detects_deleted_target(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("source");
        Path link = tempDir.resolve("link");
        Files.createDirectories(source);

        SymlinkProvisioner.link(link, source);
        assertThat(SymlinkProvisioner.isBrokenLink(link)).isFalse();

        // Delete the target — link is now broken.
        Files.delete(source);
        assertThat(SymlinkProvisioner.isBrokenLink(link)).isTrue();
    }

    @Test
    void unlink_removes_the_link_not_the_target(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("source");
        Path link = tempDir.resolve("link");
        Files.createDirectories(source);
        Files.writeString(source.resolve("file.txt"), "preserve me");

        SymlinkProvisioner.link(link, source);
        SymlinkProvisioner.unlink(link);

        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.exists(source)).isTrue();
        assertThat(Files.readString(source.resolve("file.txt"))).isEqualTo("preserve me");
    }
}
