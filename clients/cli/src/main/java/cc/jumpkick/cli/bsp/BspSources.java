// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.ide.IdeSourceRoots;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A module's source and resource directories as the BSP answers name them: {@code
 * buildTarget/sources}, {@code buildTarget/inverseSources} (the targets a file belongs to) and
 * {@code buildTarget/resources}. The roots are the ones {@code jk ide} reports, then those the
 * engine model says the build generates.
 */
final class BspSources {

    private BspSources() {}

    /** A directory {@code buildTarget/sources} reports, and whether the build generates it. */
    private record SourceRoot(Path dir, boolean generated) {}

    /** {@code SourcesItem}: the directories of module {@code i}, at {@code mod}, that exist. */
    static String sourcesItem(String tid, Path mod, @Nullable IdeWireModel model, int i) {
        List<String> sources = new ArrayList<>();
        for (SourceRoot root : roots(mod, model, i)) {
            sources.add(JsonFields.object()
                    .string("uri", BspServer.pathUri(root.dir()))
                    .number("kind", BspServer.DIRECTORY)
                    .bool("generated", root.generated())
                    .finish());
        }
        return BspServer.itemFor(tid)
                .token("sources", BspServer.arrayOf(sources))
                .finish();
    }

    /**
     * {@code InverseSourcesResult} for the request {@code message}: the module targets whose source
     * directories hold its document. A suite's target has no sources of its own, so it is never one.
     */
    static String inverseSources(@Nullable Object message, @Nullable IdeWireModel model, Path projectDir) {
        String uri = MiniJson.str(MiniJson.get(MiniJson.get(message, "params"), "textDocument"), "uri");
        Path file = uri == null ? null : uriPath(uri);
        List<String> targets = new ArrayList<>();
        if (file != null && model != null) {
            String rootUri = BspServer.pathUri(projectDir);
            for (int i = 0; i < BspTargetOptions.moduleCount(model); i++) {
                for (SourceRoot root : roots(BspTargetOptions.moduleDir(model, i, projectDir), model, i)) {
                    if (file.startsWith(WorkingDirectory.resolve(root.dir()).normalize())) {
                        targets.add(BspServer.uriJson(rootUri + "#" + BspTargetOptions.targetName(model, i)));
                        break;
                    }
                }
            }
        }
        return JsonFields.object().token("targets", BspServer.arrayOf(targets)).finish();
    }

    /** {@code ResourcesItem}: the resource directories of the module at {@code mod} that exist, main and test. */
    static String resourcesItem(String tid, Path mod) {
        List<String> resources = new ArrayList<>();
        for (IdeSourceRoots.Root root : IdeSourceRoots.of(mod)) {
            Path dir = mod.resolve(root.relative());
            if (root.resource() && Files.isDirectory(dir)) resources.add(BspServer.pathUri(dir));
        }
        return BspServer.itemFor(tid).array("resources", resources).finish();
    }

    /** The directories of module {@code i}, at {@code mod}, that exist: main, every test suite, resources, generated. */
    private static List<SourceRoot> roots(Path mod, @Nullable IdeWireModel model, int i) {
        List<SourceRoot> out = new ArrayList<>();
        for (IdeSourceRoots.Root root : IdeSourceRoots.of(mod)) {
            Path s = mod.resolve(root.relative());
            if (Files.isDirectory(s)) out.add(new SourceRoot(s, false));
        }
        if (model != null) {
            addGenerated(out, model.genSrcDirs(), i);
            addGenerated(out, model.genTestSrcDirs(), i);
        }
        return out;
    }

    private static void addGenerated(List<SourceRoot> roots, @Nullable List<String> dirs, int i) {
        if (dirs == null || i < 0 || i >= dirs.size()) return;
        String d = dirs.get(i);
        if (d == null || d.isBlank()) return;
        Path p = Path.of(d);
        if (Files.isDirectory(p)) roots.add(new SourceRoot(p, true));
    }

    /** {@code uri} as an absolute normalized path; null when it names no file. */
    private static @Nullable Path uriPath(String uri) {
        try {
            return WorkingDirectory.resolve(Path.of(URI.create(uri))).normalize();
        } catch (RuntimeException notAPath) {
            return null;
        }
    }
}
