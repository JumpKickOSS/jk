// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The files of one run directory: its record, the snapshot files beside it, and the earlier run
 * whose byte-identical snapshot files a new run links instead of writing again.
 */
final class RunFiles {

    private RunFiles() {}

    /** Move {@code name} from {@code from} into {@code to} when it is there. */
    static void moveIfPresent(Path from, Path to, String name) throws IOException {
        if (Files.isRegularFile(from.resolve(name))) {
            AtomicWrites.moveInto(from.resolve(name), to.resolve(name));
        }
    }

    /** {@code record.json}, and {@link BuildJournal#DIAGNOSTICS_SPILL} when the diagnostics overran their budget. */
    static void writeRecord(Path dir, BuildRecord record) throws IOException {
        Json.Written written = Json.write(record, BuildJournal.DIAGNOSTICS_BUDGET, BuildJournal.DIAGNOSTICS_SPILL);
        if (written.spill() != null) {
            Files.writeString(dir.resolve(BuildJournal.DIAGNOSTICS_SPILL), written.spill(), StandardCharsets.UTF_8);
        }
        Files.writeString(dir.resolve(BuildJournal.RECORD), written.record(), StandardCharsets.UTF_8);
    }

    /**
     * The snapshot files of a run. {@code previous} is the run before it, when one is on disk: a
     * source list or lockfile byte-identical to that run's is linked to its copy rather than
     * written again. Most runs change neither, and the lockfile alone is tens of kilobytes a run.
     */
    static void writeSnapshot(Path dir, BuildJournal.Snapshot s, @Nullable Path previous) throws IOException {
        if (s == null) return;
        if (s.testOutcomes() != null) {
            Files.writeString(dir.resolve(BuildJournal.TEST_OUTCOMES_TSV), s.testOutcomes(), StandardCharsets.UTF_8);
        }
        if (s.sources() != null) {
            shareOrWrite(dir.resolve(BuildJournal.SOURCES_TSV), s.sources().getBytes(StandardCharsets.UTF_8), previous);
        }
        if (s.resultsMd() != null && Files.isRegularFile(s.resultsMd())) {
            Files.copy(s.resultsMd(), dir.resolve(BuildJournal.RESULTS_MD), StandardCopyOption.REPLACE_EXISTING);
        }
        if (s.lockfile() != null && Files.isRegularFile(s.lockfile())) {
            shareOrWrite(dir.resolve(ManifestPaths.LOCK), Files.readAllBytes(s.lockfile()), previous);
        }
        if (s.diagnosticsText() != null && !s.diagnosticsText().isBlank()) {
            Files.writeString(dir.resolve(BuildJournal.DIAGNOSTICS_TXT), s.diagnosticsText(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Write {@code bytes} at {@code target}, or link it to the file of the same name in {@code
     * previous} when that holds exactly these bytes. A run's files are never rewritten once it
     * completes, so a shared inode cannot change under either run, and pruning one run leaves the
     * other's copy whole. A filesystem without links gets the bytes.
     */
    static void shareOrWrite(Path target, byte[] bytes, @Nullable Path previous) throws IOException {
        if (previous != null) {
            Path prior = previous.resolve(target.getFileName().toString());
            try {
                if (Files.isRegularFile(prior, LinkOption.NOFOLLOW_LINKS)
                        && Files.size(prior) == bytes.length
                        && Arrays.equals(Files.readAllBytes(prior), bytes)) {
                    Files.createLink(target, prior);
                    return;
                }
            } catch (IOException | UnsupportedOperationException noLink) {
                Files.deleteIfExists(target);
            }
        }
        Files.write(target, bytes);
    }

    /** How many lower-numbered directories {@link #previousRun} tries for a finished record. */
    private static final int PREVIOUS_RUN_SCAN = 8;

    /**
     * The nearest lower-numbered run directory beside {@code runDir} with a record, or {@code null}.
     * Listed rather than counted down: a run of no-ops leaves numbers with no directory.
     */
    static @Nullable Path previousRun(Path runDir) {
        Path runs = runDir.getParent();
        if (runs == null) return null;
        long n = ProjectBuilds.runNumberOf(runDir);
        List<Long> lower = new ArrayList<>();
        try {
            PathUtil.forEachChild(runs, (p, attrs) -> {
                String name = p.getFileName().toString();
                if (!attrs.isDirectory() || !ProjectBuilds.validRunDirName(name)) return true;
                try {
                    long k = Long.parseLong(name);
                    if (k > 0 && k < n) lower.add(k);
                } catch (NumberFormatException ignored) {
                    // not a run number
                }
                return true;
            });
        } catch (IOException | RuntimeException e) {
            return null;
        }
        lower.sort(Comparator.reverseOrder());
        for (int i = 0; i < Math.min(lower.size(), PREVIOUS_RUN_SCAN); i++) {
            Path candidate = runs.resolve(Long.toString(lower.get(i)));
            if (Files.isRegularFile(candidate.resolve(BuildJournal.RECORD))) return candidate;
        }
        return null;
    }
}
