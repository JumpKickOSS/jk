// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Engines a suite started inside its test-home slot: a test that runs the CLI there starts one,
 * and an engine detaches into its own session, so the suite exiting does not end it, and nothing
 * names an owner it could watch. Left alone, each idled at ~130 MB until someone found it with
 * {@code ps}. {@link TestHomes} stops them when the slot's last hold closes and before it deletes a
 * slot.
 */
final class TestHomeEngines {

    /** How long a stopped engine gets to drain before it is killed. */
    static final Duration GRACE = Duration.ofSeconds(5);

    private TestHomeEngines() {}

    /**
     * Stop every engine whose state lives under {@code slot} (its home, and the homes of the slots
     * nested in it): ask each to stop, then kill what is left after {@link #GRACE}. Only a process
     * whose command line names the slot is signalled. The count stopped; never throws.
     */
    static int stop(Path slot) {
        Path root = slot.toAbsolutePath().normalize();
        List<ProcessHandle> engines = new ArrayList<>();
        for (Path dir : engineDirs(root)) engines.addAll(running(dir, root));
        if (engines.isEmpty()) return 0;
        List<CompletableFuture<ProcessHandle>> exits = new ArrayList<>();
        for (ProcessHandle engine : engines) {
            engine.destroy();
            exits.add(engine.onExit());
        }
        try {
            CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new))
                    .get(GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception slow) {
            for (ProcessHandle engine : engines) {
                if (engine.isAlive()) engine.destroyForcibly();
            }
        }
        Log.debug("test homes: stopped " + engines.size() + " engine(s) under " + root);
        return engines.size();
    }

    /** The engine state directories of {@code slot}'s home and of each slot nested in it. */
    private static List<Path> engineDirs(Path slot) {
        List<Path> out = new ArrayList<>();
        out.add(engineDir(slot));
        try {
            PathUtil.forEachChild(slot.resolve("home").resolve(TestHomes.DIR), (nested, attrs) -> {
                if (attrs.isDirectory()) out.add(engineDir(nested));
                return true;
            });
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("test homes: cannot list the slots nested in " + slot, unreadable);
        }
        return out;
    }

    private static Path engineDir(Path slot) {
        return slot.resolve("home").resolve("state").resolve("engine");
    }

    /** The live engines a pid file under {@code dir} names whose command line points into {@code slot}. */
    private static List<ProcessHandle> running(Path dir, Path slot) {
        List<ProcessHandle> out = new ArrayList<>();
        long self = ProcessHandle.current().pid();
        try {
            PathUtil.forEachChild(dir, (file, attrs) -> {
                if (!attrs.isRegularFile() || !file.getFileName().toString().endsWith(".pid")) return true;
                long pid = pid(file);
                if (pid <= 0 || pid == self) return true;
                ProcessHandle.of(pid)
                        .filter(ProcessHandle::isAlive)
                        .filter(p -> inSlot(p, slot))
                        .filter(p -> !out.contains(p))
                        .ifPresent(out::add);
                return true;
            });
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("test homes: cannot list " + dir, unreadable);
        }
        return out;
    }

    private static long pid(Path file) {
        try {
            String first = Files.readString(file, StandardCharsets.UTF_8)
                    .lines()
                    .findFirst()
                    .orElse("")
                    .trim();
            return Long.parseLong(first);
        } catch (IOException | RuntimeException unreadable) {
            return -1;
        }
    }

    /** Whether {@code process} runs from {@code slot}: a recycled pid names some other program. */
    static boolean inSlot(ProcessHandle process, Path slot) {
        String prefix = slot.toString() + File.separator;
        return process.info().commandLine().map(line -> line.contains(prefix)).orElse(false);
    }
}
