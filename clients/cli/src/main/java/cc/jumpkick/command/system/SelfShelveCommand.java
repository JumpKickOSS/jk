// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk self shelve <repos-dir>} — hidden install-time seam for a local dist: copy every
 * artifact under {@code <repos-dir>/jk-local/} (Maven layout, the tree's module jars and POMs as
 * {@code jk build} writes them under {@code target/dist/repos/}) onto the home's shelf, {@code
 * <store>/repos/jk-local/}, each with the {@code .jk} memo the resolvers and the worker launcher
 * read. The installers run it right after materializing the engine, so the engine an install
 * spawns launches the workers built beside it rather than fetching the published ones of the same
 * version. The memo names the materialized engine of this version as the packager when the home
 * has one, the same fact {@code jk install} records.
 */
public final class SelfShelveCommand implements CliCommand {

    @Override
    public String name() {
        return "shelve";
    }

    @Override
    public String description() {
        return "Copy a dist's repos/jk-local tree onto the home's shelf";
    }

    @Override
    public boolean hidden() {
        return true;
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("repos-dir", Arity.ONE, "The dist's repos/ directory, holding jk-local/."));
    }

    @Override
    public int run(Invocation in) throws IOException {
        Path source = Path.of(in.positionals().getFirst())
                .toAbsolutePath()
                .normalize()
                .resolve(RepoArtifactResolver.JK_LOCAL);
        if (!Files.isDirectory(source)) {
            CommandWedge.printFail(
                    "Self", "no " + RepoArtifactResolver.JK_LOCAL + " shelf under " + source.getParent());
            return Exit.SOFTWARE;
        }
        List<Path> artifacts = artifactsUnder(source);
        if (artifacts.isEmpty()) {
            CommandWedge.printFail("Self", "nothing to shelve under " + source);
            return Exit.SOFTWARE;
        }
        String packagedBy = materializedEngineSha();
        Path store = JkStores.store();
        for (Path artifact : artifacts) {
            String relative = source.relativize(artifact).toString().replace('\\', '/');
            RepoArtifactStore.writeToLocalStore(store, relative, artifact, packagedBy);
        }
        CommandWedge.printOk(
                "Self",
                "Shelved " + artifacts.size() + " artifacts into "
                        + store.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL));
        return 0;
    }

    /** Every regular file under {@code root} that is an artifact, not a memo of one, in path order. */
    private static List<Path> artifactsUnder(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        PathUtil.forEachRegularFile(root, (file, attrs) -> {
            if (!isMemo(file.getFileName().toString())) out.add(file);
        });
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static boolean isMemo(String fileName) {
        return fileName.endsWith(".jk") || fileName.endsWith(".sha256");
    }

    /** The sha of this version's materialized engine jar, or null when the home holds none. */
    private static @Nullable String materializedEngineSha() {
        return EngineInstall.current()
                .resolve(JkVersion.VERSION)
                .map(EngineInstall.Materialized::engineSha)
                .orElse(null);
    }
}
