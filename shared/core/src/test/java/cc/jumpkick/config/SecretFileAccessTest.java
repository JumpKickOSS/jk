// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The secret-file permission rule: strict everywhere, and a container platform's default secret
 * mount accepted under a mount root when only its owner, root or the user, can write it. The roots
 * are a temporary directory here, so no test needs root.
 */
class SecretFileAccessTest {

    private static final String WHERE = "repositories.corp.token";

    private static Path file(Path dir, String name, String mode) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, "s3cret");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
        return file;
    }

    @Test
    void a_file_only_its_owner_can_read_is_accepted_anywhere(@TempDir Path tmp) throws IOException {
        Path token = file(tmp.resolve("home"), "token", "rw-------");

        assertThatCode(() -> new SecretFileAccess(List.of()).check(token, WHERE))
                .doesNotThrowAnyException();
    }

    @Test
    void a_readable_file_outside_the_mount_roots_is_refused_naming_each_fix(@TempDir Path tmp) throws IOException {
        Path token = file(tmp.resolve("home"), "token", "rw-r--r--");

        assertThatThrownBy(() -> new SecretFileAccess(List.of(tmp.resolve("run/secrets"))).check(token, WHERE))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith(WHERE)
                .hasMessageContaining("chmod 600 " + token)
                .hasMessageContaining("defaultMode: 0400")
                .hasMessageContaining("mode: 0400");
    }

    @Test
    void docker_and_kubernetes_default_modes_are_accepted_under_a_mount_root(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("run/secrets");
        SecretFileAccess access = new SecretFileAccess(List.of(root));

        for (String mode : List.of("r--r--r--", "rw-r--r--", "r--r-----")) {
            Path token = file(root, "token-" + mode.replace('-', '_'), mode);
            assertThatCode(() -> access.check(token, WHERE)).as(mode).doesNotThrowAnyException();
        }
    }

    @Test
    void a_mounted_file_its_group_or_others_can_write_is_refused(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("run/secrets");
        Path token = file(root, "token", "rw-rw-r--");

        assertThatThrownBy(() -> new SecretFileAccess(List.of(root)).check(token, WHERE))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("under the secret mount")
                .hasMessageContaining("can write it");
    }

    @Test
    void a_kubernetes_data_symlink_is_judged_by_the_file_it_reaches(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("var/run/secrets/kubernetes.io/serviceaccount");
        String version = "..2026_11_04_00_00_00.000000001";
        file(root.resolve(version), "token", "rw-r--r--");
        Symlinks.create(root.resolve("..data"), Path.of(version));
        Symlinks.create(root.resolve("token"), Path.of("..data/token"));

        assertThatCode(() -> new SecretFileAccess(List.of(tmp.resolve("var/run/secrets")))
                        .check(root.resolve("token"), WHERE))
                .doesNotThrowAnyException();
    }

    @Test
    void a_link_under_a_mount_root_to_a_readable_file_elsewhere_is_refused(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("run/secrets");
        Files.createDirectories(root);
        Path elsewhere = file(tmp.resolve("home"), "token", "rw-r--r--");
        Path link = Symlinks.create(root.resolve("token"), elsewhere);

        assertThatThrownBy(() -> new SecretFileAccess(List.of(root)).check(link, WHERE))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("chmod 600");
    }

    @Test
    void the_mount_root_itself_and_a_sibling_with_its_name_as_a_prefix_are_not_under_it(@TempDir Path tmp)
            throws IOException {
        Path root = tmp.resolve("run/secrets");
        Files.createDirectories(root);
        Path sibling = file(tmp.resolve("run/secrets-extra"), "token", "rw-r--r--");

        assertThatThrownBy(() -> new SecretFileAccess(List.of(root)).check(sibling, WHERE))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("chmod 600");
    }

    @Test
    void the_default_roots_are_the_docker_and_kubernetes_mounts() {
        assertThat(SecretFileAccess.MOUNT_ROOTS).containsExactly(Path.of("/run/secrets"), Path.of("/var/run/secrets"));
    }
}
