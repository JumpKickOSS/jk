// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * CAS mark-and-sweep against a live ref set. Never deletes objects newer than sweep start or younger
 * than {@link Sweep#MIN_AGE_FOR_SWEEP} (concurrent-write and stamp-race safety).
 *
 * <p>Does not touch Maven-layout jars under {@code repos/} — those are independent copies, not
 * hard links into this CAS.
 */
public final class CasSweep {

    private CasSweep() {}

    /** {@code deletedShas}: the victims' hashes — lets a same-pass evictor skip them. */
    public record Report(int deleted, long freedBytes, int kept, Set<String> deletedShas) {}

    /**
     * Walk the CAS and delete objects not present in {@code liveRefs} (subject to the age guards
     * above). {@code dryRun = true} reports what would be deleted without touching the filesystem.
     */
    public static Report sweep(Cas cas, Set<String> liveRefs, boolean dryRun) throws IOException {
        long sweepStartMillis = System.currentTimeMillis();
        long minAgeMillis = Sweep.MIN_AGE_FOR_SWEEP.toMillis();

        Path shaRoot = cas.root().resolve("sha256");
        if (!Files.isDirectory(shaRoot)) {
            return new Report(0, 0L, 0, Set.of());
        }

        int kept = 0;
        record Victim(Path file, String hex, long size) {}
        ArrayList<Victim> victims = new ArrayList<>();
        Set<String> deletedShas = new HashSet<>();
        try (Stream<Path> stream = Files.walk(shaRoot)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                // Skip atomic-write tempfiles (covered by step 1 of prune).
                String name = file.getFileName().toString();
                if (name.startsWith(".put-")) continue;

                var hexOpt = cas.hashFromPath(file);
                if (hexOpt.isEmpty()) {
                    // File in sha256/ that doesn't fit the layout — leave
                    // it alone, it isn't our garbage to collect.
                    continue;
                }
                String hex = hexOpt.get();

                if (liveRefs.contains(hex)) {
                    kept++;
                    continue;
                }

                long mtime = Files.getLastModifiedTime(file).toMillis();
                if (mtime > sweepStartMillis) continue; // concurrent write
                if (sweepStartMillis - mtime < minAgeMillis) continue; // grace period

                long size = Files.size(file);
                victims.add(new Victim(file, hex, size));
                deletedShas.add(hex);
            }
        }
        long freedBytes = 0;
        if (!dryRun) {
            for (Victim v : victims) {
                Files.deleteIfExists(v.file());
            }
        }
        for (Victim v : victims) {
            freedBytes += v.size();
        }
        return new Report(victims.size(), freedBytes, kept, deletedShas);
    }
}
