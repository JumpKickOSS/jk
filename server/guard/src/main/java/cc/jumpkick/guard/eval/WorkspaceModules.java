// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static cc.jumpkick.config.WorkspaceModules.expand;
import static cc.jumpkick.config.WorkspaceModules.isGlob;
import static cc.jumpkick.config.WorkspaceModules.memberDirs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The module directories a workspace root declares — the root itself when it is a standalone
 * project. The member list is the build's one expansion of {@code [workspace] modules}, globs
 * included, so every guard lane sees the modules the build builds.
 */
public final class WorkspaceModules {

    private WorkspaceModules() {}

    /** One answer: the module directories, and the globs among the entries it expanded. */
    private record Answer(Path root, Path manifest, List<String> globs, List<Path> modules) {}

    /**
     * What an answer rests on: the manifest's stamp, which pins a literal list, and what its globs
     * expand to now, which a new {@code libs/new/jk.toml} under {@code libs/*} moves without
     * editing any file.
     */
    private record Inputs(@Nullable FileStamp manifest, List<String> globMembers) implements StampedMemo.Settling {
        @Override
        public boolean settled() {
            return manifest != null && manifest.settled();
        }
    }

    private static final StampedMemo<Path, Inputs, Answer> MEMO = StampedMemo.bounded(64);

    /**
     * Memoised by the manifest's stamp and its globs' expansion: every lane asks, one parse answers.
     * A manifest written too recently for its stamp to vouch for its bytes is parsed and not recorded.
     */
    public static List<Path> of(Path root) throws IOException {
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return List.of();
        try {
            return MEMO.getDiscovered(manifest, WorkspaceModules::inputs, () -> answer(root, manifest))
                    .modules();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static Answer answer(Path root, Path manifest) {
        JkBuild build;
        try {
            build = JkBuildParser.parse(manifest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Path> members = members(root, build);
        List<String> globs =
                build.workspaceModules().stream().filter(g -> isGlob(g)).toList();
        return new Answer(root, manifest, globs, members.isEmpty() ? List.of(root) : members);
    }

    private static Inputs inputs(Answer a) {
        List<String> globMembers;
        try {
            globMembers = a.globs().isEmpty() ? List.of() : expand(a.root(), a.globs());
        } catch (RuntimeException unexpandable) {
            // A glob that now selects nothing: no stamp an answer was recorded under looks like it.
            globMembers = List.of("!" + unexpandable.getMessage());
        }
        return new Inputs(FileStamp.of(a.manifest()), globMembers);
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
