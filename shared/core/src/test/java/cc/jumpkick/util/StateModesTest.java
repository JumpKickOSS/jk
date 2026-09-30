// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.SysProps;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every file jk creates under the state root is {@code 0600} and every directory {@code 0700},
 * whatever the umask: the create paths carry the mode rather than inheriting the umask default,
 * which under the usual {@code 022} or {@code 002} would leave group or other bits set.
 */
@SysProps.TempRoots("jk.env.JK_STATE_DIR")
@EnabledOnOs({OS.LINUX, OS.MAC})
class StateModesTest {

    private static final Set<PosixFilePermission> GROUP_OR_OTHER = EnumSet.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);

    @Test
    void the_engine_lock_generation_lock_and_log_are_owner_only() throws IOException {
        Path engine = JkDirs.state().resolve("engine");
        try (FileChannel lock = OwnerOnlyFiles.channel(
                        engine.resolve("k.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileChannel gen = OwnerOnlyFiles.channel(
                        engine.resolve("k.gen1.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            assertThat(lock.tryLock()).isNotNull();
            assertThat(gen.tryLock()).isNotNull();
        }
        Path log = engine.resolve("k.log");
        OwnerOnlyFiles.writeString(
                log, "header\n", StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        OwnerOnlyFiles.writeString(
                log, "more\n", StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);

        assertThat(Files.readString(log)).isEqualTo("header\nmore\n");
        assertNothingLoose(engine);
    }

    @Test
    void atomic_writes_ledger_locks_and_directories_under_state_are_owner_only() throws Exception {
        Path state = JkDirs.state();
        AtomicWrites.replace(state.resolve("jk-jdks.toml"), "default = \"x\"\n");
        FileLocks.withLock(state.resolve("jk-jdks.toml.lock"), () -> {});
        AtomicWrites.replaceDurably(state.resolve("builds/projects/p/run-number"), "1\n");
        OwnerOnlyFiles.createDirectories(state.resolve("tmp/worker-gc"));

        assertThat(state.resolve("jk-jdks.toml")).exists();
        assertThat(state.resolve("jk-jdks.toml.lock")).exists();
        assertNothingLoose(state);
    }

    @Test
    void a_loose_file_already_in_state_is_tightened() throws IOException {
        Path state = JkDirs.state();
        Path toml = Files.writeString(state.resolve("loose.toml"), "a = 1\n");
        Path lock = Files.writeString(state.resolve("loose.lock"), "");
        Files.setPosixFilePermissions(toml, PosixFilePermissions.fromString("rw-rw-r--"));
        Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-rw-r--"));

        AtomicWrites.replace(toml, "a = 2\n");
        OwnerOnlyFiles.channel(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                .close();

        assertThat(Files.getPosixFilePermissions(toml)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(lock)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void a_replace_outside_state_keeps_the_mode_it_found(@TempDir Path tmp) throws IOException {
        Path shared = Files.writeString(tmp.resolve("jk-lock.toml"), "version = 1\n");
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rw-r--r--"));

        AtomicWrites.replace(shared, "version = 1\n# edited\n");

        assertThat(OwnerOnlyFiles.inState(shared)).isFalse();
        assertThat(Files.getPosixFilePermissions(shared)).isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
    }

    /** No file or directory under {@code root}, {@code root} included, lets group or others in. */
    private static void assertNothingLoose(Path root) throws IOException {
        List<String> loose;
        try (Stream<Path> paths = Files.walk(root)) {
            loose = paths.filter(p -> {
                        try {
                            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(p);
                            mode.retainAll(GROUP_OR_OTHER);
                            return !mode.isEmpty();
                        } catch (IOException e) {
                            return true;
                        }
                    })
                    .map(p -> root.relativize(p) + " " + mode(p))
                    .toList();
        }
        assertThat(loose).as("paths with group or other bits").isEmpty();
    }

    private static String mode(Path p) {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
        } catch (IOException e) {
            return "?";
        }
    }
}
