// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The versions a {@code file:} repository with no {@code maven-metadata.xml} lists: its version directories. */
final class VersionDirectories {

    private VersionDirectories() {}

    /**
     * The version directories under the local repository at {@code fetchBase}'s {@code group/artifact/} that hold the
     * POM a fetch of that version reads ({@code <artifact>-<version>.pom}), oldest first. A directory
     * without one — a jar copied in alone, a half-written install, a snapshot holding only
     * timestamped files — is no version a resolve could fetch, so it is not listed. A {@code
     * -SNAPSHOT} directory is listed like any other, and the release/snapshot policy filters it as
     * it does a catalog's entry.
     */
    static List<String> of(URI fetchBase, Coordinate coord) {
        Path root;
        try {
            root = Path.of(fetchBase);
        } catch (IllegalArgumentException | FileSystemNotFoundException notAPath) {
            return List.of();
        }
        Path dir = root.resolve(MavenLayout.metadataPath(coord)).getParent();
        if (dir == null) return List.of();
        List<String> versions = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                String version = child.getFileName().toString();
                if (holdsPom(root, coord, version)) versions.add(version);
                return true;
            });
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("versionDirectories: " + dir + " could not be listed", unreadable);
            return List.of();
        }
        versions.sort(Versions::compare);
        return List.copyOf(versions);
    }

    /** Whether the repository at {@code root} holds the POM a fetch of {@code version} reads. */
    private static boolean holdsPom(Path root, Coordinate coord, String version) {
        try {
            Coordinate at = Coordinate.of(coord.group(), coord.artifact(), version);
            return Files.isRegularFile(root.resolve(MavenLayout.pomPath(at)));
        } catch (RuntimeException notAVersion) {
            return false;
        }
    }
}
