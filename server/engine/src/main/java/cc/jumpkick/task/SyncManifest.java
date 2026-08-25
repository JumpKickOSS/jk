// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.ActionTree;
import cc.jumpkick.lock.Lockfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-project CAS reachability roots written by {@code jk sync} under {@code
 * actions/synced/<projectFingerprint>}. Keeps locked dep shas live between sync and the first
 * build (when no action record yet names them). Ages out with the action-record TTL; REFs are
 * sorted/deduped for stable bytes.
 */
public final class SyncManifest {

    /** Filename suffix relative to {@code <cacheRoot>/actions/}. */
    private SyncManifest() {}

    /** Write a manifest for {@code lock}; {@code actionRoot} is the parent of {@code keys/}. */
    public static Path write(Path actionRoot, Path lockFile, Lockfile lock) throws IOException {
        Path syncedDir = ActionTree.SYNCED.under(actionRoot);
        Files.createDirectories(syncedDir);
        String fingerprint = Sweep.projectFingerprint(lockFile);
        Path target = syncedDir.resolve(fingerprint);

        Set<String> refs = collectRefs(lock);
        StringBuilder sb = new StringBuilder();
        sb.append("PROJECT ").append(fingerprint).append('\n');
        sb.append("LOCKFILE ").append(lockFile.toAbsolutePath().normalize()).append('\n');
        sb.append("STAMP ").append(System.currentTimeMillis()).append('\n');
        for (String ref : refs) {
            sb.append("REF ").append(ref).append('\n');
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
        return target;
    }

    /** Parse one manifest file. Returns empty if the file is malformed. */
    public static Optional<Manifest> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        String project = null;
        Path lockFile = null;
        long stamp = 0;
        List<String> refs = new ArrayList<>();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            if (line.startsWith("PROJECT ")) {
                project = line.substring("PROJECT ".length()).trim();
            } else if (line.startsWith("LOCKFILE ")) {
                lockFile = Path.of(line.substring("LOCKFILE ".length()).trim());
            } else if (line.startsWith("STAMP ")) {
                try {
                    stamp = Long.parseLong(line.substring("STAMP ".length()).trim());
                } catch (NumberFormatException ignored) {
                    return Optional.empty();
                }
            } else if (line.startsWith("REF ")) {
                refs.add(line.substring("REF ".length()).trim());
            }
        }
        if (project == null) return Optional.empty();
        return Optional.of(new Manifest(project, lockFile, stamp, refs));
    }

    /** Parsed manifest; sweep callers mainly need {@link #refs}. */
    public record Manifest(String projectFingerprint, Path lockFile, long stampMillis, List<String> refs) {
        public Manifest {
            refs = List.copyOf(refs);
        }
    }

    /** Lockfile sha256s (packages without a checksum contribute nothing). */
    private static Set<String> collectRefs(Lockfile lock) {
        Set<String> sorted = new TreeSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            String checksum = pkg.checksum();
            if (checksum == null || checksum.isBlank()) continue;
            String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
            if (seen.add(hex)) sorted.add(hex);
        }
        return sorted;
    }
}
