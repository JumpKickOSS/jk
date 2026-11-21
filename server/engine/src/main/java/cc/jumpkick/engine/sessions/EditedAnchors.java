// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.layout.AnchorWatch;
import cc.jumpkick.layout.GraphLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The anchors (a config key, a column, a route, …) an episode's edited files define or use, as the
 * last graph refresh of their checkout listed them ({@link AnchorWatch}). A checkout with no graph
 * names none. Each list is read once per refresh that rewrote it.
 */
final class EditedAnchors {

    /** Checkouts whose list is kept read; the least recently asked is dropped past it. */
    static final int KEPT = 16;

    /** One checkout's list as read, with the stat of the file it was read from. */
    private record Read(@Nullable Object key, long modified, long size, Map<String, Map<String, Boolean>> byFile) {}

    private final Map<Path, Read> lists = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Read> eldest) {
            return size() > KEPT;
        }
    };

    /**
     * The anchors {@code files}, relative to {@code project}, define or use, each to whether one of
     * them defines it; empty when the checkout has no list or the files name no anchor.
     */
    Map<String, Boolean> of(String project, Collection<String> files) {
        if (project.isEmpty() || files.isEmpty()) return Map.of();
        Path dir = Path.of(project).toAbsolutePath().normalize();
        Path root;
        try {
            root = WorkspaceLocator.findRoot(dir).orElse(dir);
        } catch (IOException | RuntimeException unreadable) {
            return Map.of();
        }
        Map<String, Map<String, Boolean>> byFile = list(GraphLayout.dir(root));
        if (byFile.isEmpty()) return Map.of();
        Map<String, Boolean> out = new TreeMap<>();
        for (String file : files) {
            Map<String, Boolean> anchors = byFile.get(rel(root, dir, file));
            if (anchors != null) anchors.forEach((id, defines) -> out.merge(id, defines, Boolean::logicalOr));
        }
        return out;
    }

    /** {@code file}, relative to {@code dir}, as a path relative to {@code root} with forward slashes. */
    private static String rel(Path root, Path dir, String file) {
        String f = file.replace('\\', '/');
        if (dir.equals(root)) return f;
        return root.relativize(dir.resolve(f)).toString().replace('\\', '/');
    }

    /** The list under {@code graph}, read again only when its file changed since. */
    private synchronized Map<String, Map<String, Boolean>> list(Path graph) {
        Path file = AnchorWatch.file(graph);
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException noList) {
            lists.remove(graph);
            return Map.of();
        }
        @Nullable Object key = attrs.fileKey();
        long modified = attrs.lastModifiedTime().toMillis();
        Read was = lists.get(graph);
        if (was != null && was.modified() == modified && was.size() == attrs.size() && Objects.equals(was.key(), key))
            return was.byFile();
        Map<String, Map<String, Boolean>> byFile = AnchorWatch.anchors(graph);
        lists.put(graph, new Read(key, modified, attrs.size(), byFile));
        return byFile;
    }
}
