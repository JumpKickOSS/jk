// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class TestHomeEnginesTest {

    /**
     * A stand-in engine: a process whose command line names {@code home}, as an engine's jar path does.
     * The trailing {@code :} stops the shell from replacing itself with {@code sleep}, which would drop
     * the path from the command line.
     */
    private static Process engine(Path home) throws Exception {
        return new ProcessBuilder(
                        "sh",
                        "-c",
                        "sleep 60; :",
                        home.resolve("lib/jk-engine.jar").toString())
                .start();
    }

    private static void pidFile(Path slot, String name, long pid) throws Exception {
        Path dir = Files.createDirectories(slot.resolve("home/state/engine"));
        Files.writeString(dir.resolve(name), pid + "\n");
    }

    @Test
    void the_last_hold_on_a_slot_closing_stops_the_engines_started_in_it_and_no_other(@TempDir Path tmp)
            throws Exception {
        Path slot = Files.createDirectories(tmp.resolve("slot"));
        Path nested = Files.createDirectories(slot.resolve("home/test-homes/inner"));
        Process inSlot = engine(slot.resolve("home"));
        Process inNested = engine(nested.resolve("home"));
        Process elsewhere = engine(tmp.resolve("other/home"));
        try {
            pidFile(slot, "a.gen1.pid", inSlot.pid());
            // A pid file that names a process running from somewhere else: a recycled pid.
            pidFile(slot, "b.gen1.pid", elsewhere.pid());
            pidFile(nested, "c.gen1.pid", inNested.pid());

            TestHomes.Hold first = TestHomes.hold(slot);
            TestHomes.Hold second = TestHomes.hold(slot);
            first.close();
            assertThat(inSlot.isAlive())
                    .as("another suite still holds the slot")
                    .isTrue();

            second.close();
            assertThat(inSlot.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(inNested.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(elsewhere.isAlive())
                    .as("not run from the slot: never signalled")
                    .isTrue();
        } finally {
            inSlot.destroyForcibly();
            inNested.destroyForcibly();
            elsewhere.destroyForcibly();
        }
    }

    /**
     * A suite killed mid-run never closes its hold, so the next reap finds a kept slot nobody holds:
     * it stops that slot's engines once the slot has sat unused past the window, keeps the slot,
     * and leaves the engines of a held slot and of one used just now alone.
     */
    @Test
    void the_reaper_stops_the_engines_of_a_slot_whose_suite_died(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("test-homes"));
        Path module = Files.createDirectories(tmp.resolve("module"));
        Path orphan = Files.createDirectories(root.resolve("orphan"));
        Path recent = Files.createDirectories(root.resolve("recent"));
        Path held = Files.createDirectories(root.resolve("held"));
        long now = System.currentTimeMillis();
        for (Path slot : new Path[] {orphan, recent, held}) TestHomes.stamp(slot, module);
        long old = now - TestHomes.ENGINE_IDLE_MILLIS - 60_000;
        Files.setLastModifiedTime(orphan.resolve(".used-at"), FileTime.fromMillis(old));
        Files.setLastModifiedTime(held.resolve(".used-at"), FileTime.fromMillis(old));
        Process dead = engine(orphan.resolve("home"));
        Process busy = engine(recent.resolve("home"));
        Process live = engine(held.resolve("home"));
        TestHomes.Hold hold = TestHomes.hold(held);
        try {
            pidFile(orphan, "a.gen1.pid", dead.pid());
            pidFile(recent, "a.gen1.pid", busy.pid());
            pidFile(held, "a.gen1.pid", live.pid());

            TestHomes.reapStale(root, now, Long.MAX_VALUE);

            assertThat(dead.waitFor(10, TimeUnit.SECONDS))
                    .as("its suite is gone")
                    .isTrue();
            assertThat(orphan).as("the slot itself is kept").isDirectory();
            assertThat(busy.isAlive()).as("used inside the window").isTrue();
            assertThat(live.isAlive()).as("a live suite holds it").isTrue();
        } finally {
            hold.close();
            dead.destroyForcibly();
            busy.destroyForcibly();
            live.destroyForcibly();
        }
    }
}
