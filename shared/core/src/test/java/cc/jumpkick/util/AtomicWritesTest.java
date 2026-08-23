// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class AtomicWritesTest {

    /**
     * Sum of {@code moveInto}'s seven back-off sleeps. A retrying move cannot finish faster than
     * this, and a single failing rename cannot plausibly take this long — so the two tests below
     * bracket the constant from either side.
     */
    private static final long BACK_OFF_SUM_MS = 5 + 10 + 15 + 20 + 25 + 30 + 35;

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
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void move_into_does_not_retry_a_posix_permission_denial(@TempDir Path dir) throws IOException {
        Path tmp = Files.writeString(dir.resolve("fresh.tmp"), "fresh");
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(locked), "running as root — the mode bits deny nothing");

            // One untimed failure first: class loading and JIT of the failing rename must not land
            // inside the window, or a cold run measures the JVM rather than the back-off.
            denyMove(tmp, locked.resolve("target"));

            long start = System.nanoTime();
            IOException thrown = denyMove(tmp, locked.resolve("target"));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertThat(thrown).isInstanceOf(AccessDeniedException.class);
            // EACCES is permanent: waiting cannot turn it into a success, and every caller
            // (JdkInventory, LockfileWriter, …) would pay the wait.
            assertThat(elapsedMs).as("failed without backing off").isLessThan(BACK_OFF_SUM_MS);
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

            long start = System.nanoTime();
            IOException thrown = denyMove(tmp, locked.resolve("target"));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertThat(thrown).isInstanceOf(AccessDeniedException.class);
            assertThat(elapsedMs).as("exhausted the back-off before giving up").isGreaterThanOrEqualTo(BACK_OFF_SUM_MS);
        } finally {
            if (realOs == null) System.clearProperty("os.name");
            else System.setProperty("os.name", realOs);
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void replace_bytes_round_trips(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("bytes.bin");
        byte[] payload = "raw".getBytes(StandardCharsets.UTF_8);
        AtomicWrites.replace(target, payload);
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
    }

    /** Runs a move that must fail, returning the exception so the caller can time the call alone. */
    private static IOException denyMove(Path tmp, Path target) {
        try {
            AtomicWrites.moveInto(tmp, target);
            return null;
        } catch (IOException e) {
            return e;
        }
    }
}
