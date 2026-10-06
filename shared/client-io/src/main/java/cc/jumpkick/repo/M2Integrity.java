// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks Maven local repository files against the {@code .sha1} Maven wrote beside them. Bounded by
 * the paths asked about — a lock's POMs and primary artifacts — so it never hashes all of
 * {@code ~/.m2}. A file with no sidecar, or a sidecar that is not a SHA-1, is not judged.
 */
public final class M2Integrity {

    private M2Integrity() {}

    /** The POM and primary artifact path of every Maven row in {@code lock}, Maven-relative. */
    public static List<String> lockPaths(Lockfile lock) {
        Set<String> paths = new LinkedHashSet<>();
        for (Lockfile.Artifact a : lock.artifacts()) {
            if (a.name().indexOf(':') < 0) continue;
            Coordinate coord = a.coordinate();
            paths.add(MavenLayout.pomPath(coord));
            paths.add(MavenLayout.artifactPath(coord));
        }
        return List.copyOf(paths);
    }

    /** The files under {@code m2} among {@code relativePaths} whose bytes do not hash to their {@code .sha1}. */
    public static List<Path> mismatched(Path m2, List<String> relativePaths) {
        List<Path> bad = new ArrayList<>();
        for (String rel : relativePaths) {
            Path file;
            try {
                file = MavenLayout.safeResolve(m2, rel);
            } catch (RuntimeException escaping) {
                continue;
            }
            if (!matchesSha1(file)) bad.add(file);
        }
        return bad;
    }

    /** False only when {@code file} and a well-formed {@code .sha1} beside it both exist and disagree. */
    static boolean matchesSha1(Path file) {
        Path sidecar = file.resolveSibling(file.getFileName() + ".sha1");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(sidecar)) return true;
        try {
            Optional<String> want = Hashing.checksumFromSidecar(Files.readString(sidecar), 40);
            return want.isEmpty() || want.get().equals(Hashing.fileHex("SHA-1", file));
        } catch (IOException unreadable) {
            return true;
        }
    }
}
