// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.PackageIndex;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Which coordinates in the local artifact store ship a package. The search starts at the longest
 * directory prefix of the package that exists under a repository ({@code org/springframework} for
 * {@code org.springframework.web.bind.annotation}) and reads each jar's package list through
 * {@link PackageIndex}, so a jar is unzipped once per store. At most two coordinates, cached under
 * the index directory when the walk finishes.
 */
final class StorePackages {

    /** Jars examined under one repository. Past this the answer is partial and is not cached. */
    static final int JAR_LIMIT = 160;

    private StorePackages() {}

    /** Up to two {@code group:artifact} values whose jar contains {@code pkg}, or empty. */
    static List<String> find(Path store, String pkg, Path indexDir) {
        Path cached = indexDir.resolve("store").resolve(pkg + ".txt");
        List<String> hit = read(cached);
        if (hit != null) return hit;
        Path repos = store.resolve("repos");
        if (!Files.isDirectory(repos)) return List.of();
        String[] segments = pkg.split("\\.");
        if (segments.length < 2) return List.of();
        List<String> found = new ArrayList<>();
        boolean[] capped = {false};
        try {
            PathUtil.forEachChild(repos, (repo, attrs) -> {
                if (!attrs.isDirectory() || found.size() == 2) return found.size() < 2;
                Path base = deepest(repo, segments);
                if (base == null) return true;
                int[] seen = {0};
                PathUtil.forEachEntry(base, dir -> dir.getNameCount() - base.getNameCount() > 3, (path, fileAttrs) -> {
                    if (!fileAttrs.isRegularFile()) return true;
                    String file = path.getFileName().toString();
                    if (!file.endsWith(".jar") || file.contains("-sources") || file.contains("-javadoc")) return true;
                    if (++seen[0] > JAR_LIMIT) {
                        capped[0] = true;
                        return false;
                    }
                    if (!PackageIndex.packagesOf(path, null, indexDir).contains(pkg)) return true;
                    String ga = coordinate(repo, path);
                    if (ga != null && !found.contains(ga)) found.add(ga);
                    return found.size() < 2;
                });
                return found.size() < 2 && !capped[0];
            });
        } catch (IOException e) {
            return List.of();
        }
        boolean complete = !capped[0];
        if (complete) write(cached, found);
        return found;
    }

    /** The deepest directory under {@code repo} named by a prefix of {@code segments}. */
    private static @Nullable Path deepest(Path repo, String[] segments) {
        Path cursor = repo;
        boolean stepped = false;
        for (String segment : segments) {
            Path next = cursor.resolve(segment);
            if (!Files.isDirectory(next)) break;
            cursor = next;
            stepped = true;
        }
        return stepped ? cursor : null;
    }

    /**
     * {@code group:artifact} from a Maven-layout jar: the version directory's parent is the
     * artifact, and the directories above that, under the repository root, are the group.
     */
    static @Nullable String coordinate(Path repo, Path jar) {
        Path version = jar.getParent();
        if (version == null) return null;
        Path artifactDir = version.getParent();
        if (artifactDir == null) return null;
        Path group = artifactDir.getParent();
        if (group == null) return null;
        Path relative = repo.relativize(group);
        if (relative.toString().isEmpty() || relative.startsWith("..")) return null;
        String groupId = relative.toString().replace('\\', '/').replace('/', '.');
        return groupId + ":" + artifactDir.getFileName();
    }

    private static @Nullable List<String> read(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) out.add(line.strip());
            }
            return out.size() > 2 ? out.subList(0, 2) : out;
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(Path file, List<String> coordinates) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, coordinates, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The list is a cache; the next lookup walks the jars again.
        }
    }
}
