// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class AtomicWritesTest {

    /**
     * Redirect {@link AtomicWrites#backOff} into the returned list for the rest of this test, and
     * put it back afterwards. The two tests below assert retry counts, not wall-clock budgets.
     */
    private List<Integer> countBackOffs() {
        List<Integer> seen = new ArrayList<>();
        IntConsumer previous = AtomicWrites.backOff;
        AtomicWrites.backOff = seen::add;
        restoreBackOff = () -> AtomicWrites.backOff = previous;
        return seen;
    }

    /** Set by {@link #countBackOffs}; puts the production sleeper back after the test. */
    private @Nullable Runnable restoreBackOff;

    @AfterEach
    void putTheBackOffBack() {
        if (restoreBackOff != null) {
            restoreBackOff.run();
            restoreBackOff = null;
        }
    }

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
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void replace_leaves_an_existing_files_mode_alone(@TempDir Path dir) throws IOException {
        // Replace preserves the existing file's POSIX mode; the staging file's 0600 must not stick.
        Path target = dir.resolve("jk-lock.toml");
        Files.writeString(target, "before");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));

        AtomicWrites.replace(target, "after");

        assertThat(Files.readString(target)).isEqualTo("after");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
                .as("a replace changes the contents, not the mode")
                .isEqualTo("rw-r--r--");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void replace_preserves_a_tight_mode_too(@TempDir Path dir) throws IOException {
        // The rule is "keep what you found", not "widen". A target somebody deliberately locked down
        // stays locked down — otherwise the fix for the leak would itself be a leak.
        Path target = dir.resolve("secret.toml");
        Files.writeString(target, "before");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));

        AtomicWrites.replace(target, "after");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
                .isEqualTo("rw-------");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void a_fresh_replace_lands_at_the_same_mode_a_plain_write_would(@TempDir Path dir) throws IOException {
        // No target to copy a mode from, so the answer has to be the process default — whatever the
        // umask says, asserted against Files.write rather than against a hardcoded 0644, because the
        // umask running the suite is not ours to choose.
        Path reference = dir.resolve("reference.txt");
        Files.write(reference, "x".getBytes(StandardCharsets.UTF_8));

        Path target = dir.resolve("fresh.txt");
        AtomicWrites.replace(target, "x");

        assertThat(Files.getPosixFilePermissions(target))
                .as("a created file is an ordinary file")
                .isEqualTo(Files.getPosixFilePermissions(reference));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void replaceDurably_follows_the_same_rule(@TempDir Path dir) throws IOException {
        // The lockfile goes through replaceDurably; that path must preserve mode too.
        Path target = dir.resolve("jk-lock.toml");
        Files.writeString(target, "before");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));

        AtomicWrites.replaceDurably(target, "after");

        assertThat(Files.readString(target)).isEqualTo("after");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
                .isEqualTo("rw-r--r--");

        Path fresh = dir.resolve("fresh-durable.txt");
        Path reference = dir.resolve("reference-durable.txt");
        Files.write(reference, "x".getBytes(StandardCharsets.UTF_8));
        AtomicWrites.replaceDurably(fresh, "x");
        assertThat(Files.getPosixFilePermissions(fresh)).isEqualTo(Files.getPosixFilePermissions(reference));
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
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void move_into_does_not_retry_a_posix_permission_denial(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("fresh.tmp"), "fresh");
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(locked), "running as root — the mode bits deny nothing");

            List<Integer> backOffs = countBackOffs();
            IOException thrown = denyMove(tmp, locked.resolve("target"));

            assertThat(thrown).isInstanceOf(AccessDeniedException.class);
            // EACCES is permanent: waiting cannot turn it into a success, and every caller
            // (JdkInventory, LockfileWriter, …) would pay the wait.
            assertThat(backOffs).as("failed without backing off").isEmpty();
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /**
     * The Windows retry is gated on a live {@code os.name} read, so a spoofed host is the only way
     * to reach it from Linux. It proves the gate, not that Windows recovers — a real transient
     * sharing violation cannot be produced here.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void move_into_retries_when_the_host_reports_windows(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("fresh.tmp"), "fresh");
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        String realOs = System.getProperty("os.name");
        try {
            assumeFalse(Files.isWritable(locked), "running as root — the mode bits deny nothing");
            System.setProperty("os.name", "Windows 11");

            List<Integer> backOffs = countBackOffs();
            IOException thrown = denyMove(tmp, locked.resolve("target"));

            assertThat(thrown).isInstanceOf(AccessDeniedException.class);
            assertThat(backOffs)
                    .as("exhausted the back-off before giving up")
                    .hasSize(31)
                    .startsWith(1, 2, 3)
                    .endsWith(31);
        } finally {
            if (realOs == null) System.clearProperty("os.name");
            else System.setProperty("os.name", realOs);
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /**
     * OpenJDK maps {@code ERROR_SHARING_VIOLATION} to a bare {@link FileSystemException}, not
     * {@link AccessDeniedException}. Both must count as a transient Windows lock; typed subclasses
     * and plain {@link IOException} must not.
     */
    @Test
    void windows_treats_access_denied_and_a_bare_sharing_violation_as_transient() {
        assertThat(AtomicWrites.isTransientWindowsLock(new AccessDeniedException("a")))
                .isTrue();
        assertThat(AtomicWrites.isTransientWindowsLock(new FileSystemException("a", "b", "in use")))
                .isTrue();
        assertThat(AtomicWrites.isTransientWindowsLock(new NoSuchFileException("a")))
                .isFalse();
        assertThat(AtomicWrites.isTransientWindowsLock(new FileAlreadyExistsException("a")))
                .isFalse();
        assertThat(AtomicWrites.isTransientWindowsLock(new IOException("disk full")))
                .isFalse();
    }

    @Test
    void replace_bytes_round_trips(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("bytes.bin");
        byte[] payload = "raw".getBytes(StandardCharsets.UTF_8);
        AtomicWrites.replace(target, payload);
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
    }

    /** Runs a move that must fail, returning the exception so the caller can time the call alone. */
    @Test
    void read_string_does_not_retry_a_missing_file(@TempDir Path dir) {
        List<Integer> backOffs = countBackOffs();

        assertThatIOException()
                .isThrownBy(() -> AtomicWrites.readString(dir.resolve("never-written")))
                .isInstanceOf(NoSuchFileException.class);
        // A name that is gone is gone; only the caller knows whether that is a miss or a fault.
        assertThat(backOffs).as("waited for a file that will not appear").isEmpty();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void read_string_does_not_retry_a_posix_permission_denial(@TempDir Path dir) throws IOException {
        Path unreadable = Files.writeString(dir.resolve("secret"), "x");
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(unreadable), "running as root — the mode bits deny nothing");

        List<Integer> backOffs = countBackOffs();
        IOException thrown = denyRead(unreadable);

        assertThat(thrown).isInstanceOf(AccessDeniedException.class);
        assertThat(backOffs)
                .as("EACCES is permanent for a read as it is for a move")
                .isEmpty();
    }

    /**
     * As with the move, the Windows retry is gated on a live {@code os.name} read, so a spoofed
     * host is the only way to reach it from Linux. It proves the gate, not that Windows recovers.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void read_string_retries_when_the_host_reports_windows(@TempDir Path dir) throws IOException {
        Path unreadable = Files.writeString(dir.resolve("secret"), "x");
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        String realOs = System.getProperty("os.name");
        try {
            assumeFalse(Files.isReadable(unreadable), "running as root — the mode bits deny nothing");
            System.setProperty("os.name", "Windows 11");

            List<Integer> backOffs = countBackOffs();
            IOException thrown = denyRead(unreadable);

            assertThat(thrown).isInstanceOf(AccessDeniedException.class);
            assertThat(backOffs)
                    .as("a denied read spends the same budget the denied move does")
                    .hasSize(31)
                    .startsWith(1, 2, 3)
                    .endsWith(31);
        } finally {
            if (realOs == null) System.clearProperty("os.name");
            else System.setProperty("os.name", realOs);
        }
    }

    private static @Nullable IOException denyRead(Path target) {
        try {
            AtomicWrites.readString(target);
            return null;
        } catch (IOException e) {
            return e;
        }
    }

    private static @Nullable IOException denyMove(Path tmp, Path target) {
        try {
            AtomicWrites.moveInto(tmp, target);
            return null;
        } catch (IOException e) {
            return e;
        }
    }

    /**
     * The temp sibling is cleaned up when the write fails, and does not outlive one that succeeds.
     * Cleanup runs on the failure path only — {@link AtomicWrites#moveInto} already consumed the temp
     * on success.
     */
    @Test
    void a_failed_move_still_removes_the_temp(@TempDir Path dir) throws IOException {
        // A non-empty directory cannot be replaced by a file move, so moveInto throws after the temp is written.
        Path target = dir.resolve("occupied");
        Files.createDirectory(target);
        Files.createFile(target.resolve("child"));

        assertThatIOException().isThrownBy(() -> AtomicWrites.replace(target, "payload"));

        assertThat(names(dir)).containsExactly("occupied");
    }

    @Test
    void repeated_writes_leave_no_temps_behind(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("counter");
        for (int i = 0; i < 25; i++) {
            AtomicWrites.replace(target, "n = " + i);
        }

        assertThat(Files.readString(target)).isEqualTo("n = 24");
        assertThat(names(dir)).containsExactly("counter");
    }

    private static List<String> names(Path dir) throws IOException {
        try (var children = Files.list(dir)) {
            return children.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * The durable variant exists, works, and is <em>not</em> what {@link AtomicWrites#replace} does.
     *
     * <p>The second half is the part worth guarding. Roughly thirty-five of the fifty call sites write
     * reconstructible, fail-open data, and {@code replace} already costs 372.7 µs on Windows — so a
     * well-meaning change that made every write durable would cost far more than the durability it
     * bought. There is no portable way to observe an fsync from a test, so this pins the surface
     * instead: two distinct methods, and the cheap one does not delegate to the expensive one
     *.
     */
    @Test
    void the_durable_variant_is_separate_from_the_default(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("lock.toml");

        AtomicWrites.replaceDurably(target, "durable");
        assertThat(Files.readString(target)).isEqualTo("durable");
        assertThat(names(dir)).containsExactly("lock.toml");

        AtomicWrites.replaceDurably(target, "again-and-longer");
        assertThat(Files.readString(target)).isEqualTo("again-and-longer");

        // The cheap path must remain the cheap path: if replace ever delegated to replaceDurably,
        // every advisory write in the tree would start paying for durability nobody consumes.
        String source = Files.readString(RepoRoot.dir(AtomicWritesTest.class, "shared/core")
                .resolve("src/main/java/cc/jumpkick/util/AtomicWrites.java"));
        int replaceBody = source.indexOf("public static void replace(Path target, byte[] bytes)");
        // Bound at this method's own closing brace: replaceDurably sits between the two replace
        // overloads, so slicing to the next one would read its javadoc and always match.
        int endOfBody = source.indexOf("\n    }", replaceBody);
        assertThat(source.substring(replaceBody, endOfBody))
                .as("replace(Path, byte[]) must not route through the durable variant")
                .doesNotContain("replaceDurably")
                .doesNotContain("force(");
    }

    @Test
    void a_failed_durable_move_still_removes_the_temp(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("occupied");
        Files.createDirectory(target);
        Files.createFile(target.resolve("child"));

        assertThatIOException().isThrownBy(() -> AtomicWrites.replaceDurably(target, "payload"));

        assertThat(names(dir)).containsExactly("occupied");
    }

    /** A target given as a bare file name lives in the working directory; the staging sibling does too. */
    @Test
    void replace_accepts_a_bare_relative_target() throws IOException {
        Path target = Path.of(".atomic-writes-test-" + UUID.randomUUID() + ".tmp");
        try {
            AtomicWrites.replace(target, "bare");
            assertThat(Files.readString(target)).isEqualTo("bare");
            AtomicWrites.replaceDurably(target, "again");
            assertThat(Files.readString(target)).isEqualTo("again");
        } finally {
            Files.deleteIfExists(target);
        }
    }
}
