// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.Symlinks;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A socket path past the OS limit cannot be bound, so an engine directory too deep for one binds
 * through a short link to itself; one that fits binds where it always has.
 */
class EnginePathsSocketDirTest {

    private static Path deep(Path base) {
        return base.resolve("x".repeat(60)).resolve("y".repeat(60)).resolve("engine");
    }

    @Test
    void a_short_engine_dir_binds_in_place() {
        Path dir = Path.of("/home/u/.jk/state/engine");
        Assumptions.assumeFalse(EngineTransport.useLoopbackTcp());
        assertThat(EnginePaths.socketDir(dir)).isEqualTo(dir);
        EnginePaths.Paths paths = EnginePaths.forKey("0123456789abcdef", Path.of("/home/u/.jk/state"));
        assertThat(paths.socket().getParent()).isEqualTo(dir);
    }

    @Test
    void a_deep_engine_dir_binds_through_a_link_that_fits() {
        Assumptions.assumeFalse(EngineTransport.useLoopbackTcp());
        Path state = Path.of("/tmp/a", "x".repeat(60), "y".repeat(60));
        EnginePaths.Paths paths = EnginePaths.forKey("0123456789abcdef", state);
        Path gen = EnginePaths.generation(paths, 123_456).socket();

        assertThat(paths.socket().getParent()).isNotEqualTo(paths.dir());
        assertThat(gen.toString().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(EnginePaths.socketPathLimit());
        assertThat(paths.lock().getParent()).as("every other file stays put").isEqualTo(paths.dir());
    }

    @Test
    void the_link_is_stable_per_directory_and_distinct_across_them() {
        Path a = deep(Path.of("/tmp/a"));
        Path b = deep(Path.of("/tmp/b"));
        assertThat(EnginePaths.shortLinkFor(a)).isEqualTo(EnginePaths.shortLinkFor(a));
        assertThat(EnginePaths.shortLinkFor(a)).isNotEqualTo(EnginePaths.shortLinkFor(b));
        assertThat(EnginePaths.shortLinkFor(a).getParent())
                .isEqualTo(EnginePaths.shortLinkFor(b).getParent());
    }

    @Test
    void the_limit_is_measured_in_bytes_of_the_longest_generation_socket() {
        assertThat(EnginePaths.fits(Path.of("/s"), 107)).isTrue();
        Path atLimit = Path.of("/" + "d".repeat(107 - 1 - 1 - 16 - ".gen999999.sock".length()));
        assertThat(EnginePaths.fits(atLimit, 107)).isTrue();
        assertThat(EnginePaths.fits(atLimit.resolveSibling(atLimit.getFileName() + "d"), 107))
                .isFalse();
    }

    @Test
    void the_link_reaches_the_engine_dir_and_its_siblings(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(tmp, PosixFileAttributeView.class) != null);
        Path engineDir = Files.createDirectories(deep(tmp));
        Path root = Files.createDirectory(
                tmp.resolve("root"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path link = root.resolve("abc");

        EnginePaths.ensureLink(link, engineDir);
        Files.writeString(engineDir.resolve("k.gen1.pid"), "42\n");

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(EnginePaths.pidFor(link.resolve("k.gen1.sock"))).content().isEqualTo("42\n");

        // A link left pointing elsewhere is repaired.
        Files.delete(link);
        Symlinks.create(link, tmp);
        EnginePaths.ensureLink(link, engineDir);
        assertThat(Files.readSymbolicLink(link))
                .isEqualTo(engineDir.toAbsolutePath().normalize());
    }

    @Test
    void a_link_root_open_to_others_is_refused(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(tmp, PosixFileAttributeView.class) != null);
        Path engineDir = Files.createDirectories(deep(tmp));
        Path root = Files.createDirectory(
                tmp.resolve("open"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThatThrownBy(() -> EnginePaths.ensureLink(root.resolve("abc"), engineDir))
                .hasMessageContaining("mode 0700");
        assertThat(root.resolve("abc")).doesNotExist();
    }
}
