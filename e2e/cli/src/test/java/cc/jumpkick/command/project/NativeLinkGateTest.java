// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The template catalog's native links take turns with its builds across JVMs, as the shards' test
 * JVMs do: builds share, a link waits for them, and a build that comes after a waiting link waits
 * for that link to finish.
 */
class NativeLinkGateTest {

    /** How long a turn that should come is waited for. */
    private static final long COMES_WITHIN_MS = 30_000;

    /** How long a turn that should not come is given to show it does. */
    private static final long STAYS_OUT_MS = 500;

    @TempDir
    Path dir;

    private final List<Process> turns = new ArrayList<>();

    @AfterEach
    void stopTurns() {
        turns.forEach(Process::destroyForcibly);
    }

    @Test
    void a_waiting_link_lets_running_builds_finish_and_keeps_later_builds_out() throws Exception {
        Path first = turn("build", "first");
        Path second = turn("build", "second");
        awaitInside(first);
        awaitInside(second);

        Path link = turn("link", "link");
        awaitDoorHeld();
        assertStaysOut(link, "a link while builds hold the room");

        Path later = turn("build", "later");
        assertStaysOut(later, "a build behind a waiting link");

        leave(first);
        assertStaysOut(link, "a link while one build still holds the room");
        leave(second);
        awaitInside(link);
        assertStaysOut(later, "a build while the link runs");

        leave(link);
        awaitInside(later);
        leave(later);
        for (Process p : turns) {
            assertThat(p.waitFor(COMES_WITHIN_MS, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(p.exitValue()).as("a turn's JVM exits clean").isZero();
        }
    }

    /** Starts a JVM that takes a {@code mode} turn at the gate in {@link #dir}; its marker. */
    private Path turn(String mode, String name) throws IOException {
        Path marker = dir.resolve(name);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                NativeLinkGate.class.getName(),
                dir.toString(),
                mode,
                marker.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(dir.resolve(name + ".log").toFile());
        turns.add(pb.start());
        return marker;
    }

    private static void awaitInside(Path marker) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMES_WITHIN_MS);
        while (!Files.exists(inside(marker)) && System.nanoTime() < until) Thread.sleep(20);
        assertThat(inside(marker)).as("%s's turn came", marker.getFileName()).exists();
    }

    private static void assertStaysOut(Path marker, String what) throws InterruptedException {
        Thread.sleep(STAYS_OUT_MS);
        assertThat(inside(marker)).as(what).doesNotExist();
    }

    /** Waits until another JVM holds the door: the link has taken it and waits for the room. */
    private void awaitDoorHeld() throws Exception {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMES_WITHIN_MS);
        try (FileChannel door = FileChannel.open(
                dir.resolve(NativeLinkGate.DOOR),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            while (System.nanoTime() < until) {
                FileLock free = door.tryLock();
                if (free == null) return;
                free.release();
                Thread.sleep(20);
            }
        }
        throw new AssertionError("the link never took the door");
    }

    private static void leave(Path marker) throws IOException {
        Files.createFile(Path.of(marker + ".out"));
    }

    private static Path inside(Path marker) {
        return Path.of(marker + ".in");
    }
}
