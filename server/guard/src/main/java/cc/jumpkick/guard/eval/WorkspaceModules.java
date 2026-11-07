// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static cc.jumpkick.config.WorkspaceModules.expand;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The module directories a workspace root declares — the root itself when it is a standalone
 * project. The member list is the build's one expansion of {@code [workspace] modules}, globs
 * included, so every guard lane sees the modules the build builds.
 */
public final class WorkspaceModules {

    private WorkspaceModules() {}

    private record Memo(long size, FileTime mtime, List<Path> modules) {}

    private static final Map<Path, Memo> MEMO = new ConcurrentHashMap<>();

    /** Memoised by the manifest's size and modification time: every lane asks, one parse answers. */
    public static List<Path> of(Path root) throws IOException {
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return List.of();
        BasicFileAttributes a = Files.readAttributes(manifest, BasicFileAttributes.class);
        Memo m = MEMO.get(manifest);
        if (m != null && m.size() == a.size() && m.mtime().equals(a.lastModifiedTime())) return m.modules();
        List<Path> modules = List.copyOf(parse(root, manifest));
        MEMO.put(manifest, new Memo(a.size(), a.lastModifiedTime(), modules));
        return modules;
    }

    /**
     * The member directories {@code build}, the manifest at {@code root}, lists: its {@code
     * [workspace] modules} expanded as the build expands them, each resolved against {@code root};
     * empty when it lists none.
     */
    public static List<Path> members(Path root, JkBuild build) {
        List<Path> out = new ArrayList<>();
        for (String m : expand(root, build.workspaceModules()))
            out.add(root.resolve(m).normalize());
        return out;
    }

    private static List<Path> parse(Path root, Path manifest) throws IOException {
        List<Path> members = members(root, JkBuildParser.parse(manifest));
        return members.isEmpty() ? List.of(root) : members;
    }
}
