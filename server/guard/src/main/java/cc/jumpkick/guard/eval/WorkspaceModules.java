// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static cc.jumpkick.config.WorkspaceModules.expand;
import static cc.jumpkick.config.WorkspaceModules.isGlob;
import static cc.jumpkick.config.WorkspaceModules.memberDirs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The module directories a workspace root declares — the root itself when it is a standalone
 * project. The member list is the build's one expansion of {@code [workspace] modules}, globs
 * included, so every guard lane sees the modules the build builds.
 */
public final class WorkspaceModules {

    private WorkspaceModules() {}

    /**
     * One answer and what it rests on: the manifest's stamp, which pins a literal list, and what the
     * manifest's globs expanded to, which a new {@code libs/new/jk.toml} under {@code libs/*} moves
     * without editing any file.
     */
    private record Memo(FileStamp manifest, List<String> globs, List<String> globMembers, List<Path> modules) {

        boolean stillMatches(Path root, @Nullable FileStamp now) {
            if (!manifest.equals(now)) return false;
            if (globs.isEmpty()) return true;
            try {
                return expand(root, globs).equals(globMembers);
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    private static final Map<Path, Memo> MEMO = new ConcurrentHashMap<>();

    /**
     * Memoised by the manifest's stamp and its globs' expansion: every lane asks, one parse answers.
     * A manifest written too recently for its stamp to vouch for its bytes is parsed and not recorded.
     */
    public static List<Path> of(Path root) throws IOException {
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return List.of();
        FileStamp stamp = FileStamp.of(manifest);
        Memo m = MEMO.get(manifest);
        if (m != null && m.stillMatches(root, stamp)) return m.modules();
        JkBuild build = JkBuildParser.parse(manifest);
        List<Path> members = members(root, build);
        List<Path> modules = members.isEmpty() ? List.of(root) : members;
        if (stamp != null && stamp.settled()) {
            List<String> globs =
                    build.workspaceModules().stream().filter(g -> isGlob(g)).toList();
            List<String> globMembers = globs.isEmpty() ? List.of() : expand(root, globs);
            MEMO.put(manifest, new Memo(stamp, globs, globMembers, modules));
        } else {
            MEMO.remove(manifest);
        }
        return modules;
    }

    /**
     * The member directories {@code build}, the manifest at {@code root}, lists: its {@code
     * [workspace] modules} expanded as the build expands them, each resolved against {@code root};
     * empty when it lists none.
     */
    public static List<Path> members(Path root, JkBuild build) {
        return memberDirs(root, build);
    }
}
