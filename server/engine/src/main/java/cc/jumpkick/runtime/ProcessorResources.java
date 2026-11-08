// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.ModuleLayoutPlugins;
import cc.jumpkick.layout.ResourceFilter;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.FileHashMemo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The main resources compile-main's annotation processors can read. {@code copy-resources} lays
 * them into the class output before the compile, as Maven's {@code process-resources} does, so a
 * processor reads them through the Filer — Helidon's codegen merges a module's static {@code
 * META-INF/helidon/manifest} into the one it writes. They are therefore an input of every compile
 * that runs processors: a resource edit has to recompile, or a cached compile would bring back what
 * the processor made of the old bytes.
 *
 * <p>The digest is taken from what the copy reads, not from what it writes: each root's files by
 * module-relative path and content, filtered roots as written in the source tree with the values
 * they expand against beside them. The build and {@code jk explain} hash the same files that way,
 * and neither has to stage a filtered root to do it.
 */
public record ProcessorResources(Path moduleDir, JkBuild build, boolean compact) {

    /** The digest of every main resource the copy would lay into {@code layout}'s classes tree. */
    public String digest(BuildLayout layout) throws IOException {
        StringBuilder sb = new StringBuilder();
        Path module = moduleDir.toAbsolutePath().normalize();
        for (Path root : roots(layout)) {
            Map<String, String> files = new TreeMap<>();
            PathUtil.forEachRegularFile(
                    root,
                    (file, attrs) -> files.put(
                            slashed(root.relativize(file)),
                            FileHashMemo.contentHash(file.toAbsolutePath().normalize(), attrs)));
            if (files.isEmpty()) continue;
            sb.append("root:").append(slashed(module.relativize(root))).append('\n');
            files.forEach((rel, sha) -> sb.append(rel).append('=').append(sha).append('\n'));
        }
        BuildBlock.Resources r = build.build().resources();
        if (!r.filtered().isEmpty()) {
            new TreeMap<>(ResourceFilter.values(build))
                    .forEach((k, v) ->
                            sb.append("value:").append(k).append('=').append(v).append('\n'));
        }
        Path manifest = moduleDir.resolve(ManifestPaths.PLUGIN_MANIFEST);
        if (Files.isRegularFile(manifest)) {
            sb.append("manifest=").append(FileHashMemo.contentHash(manifest)).append('\n');
        }
        return Hashing.sha256Hex(sb.toString());
    }

    /** The directories the copy reads, in its order; filtered roots as their source, not their staged copy. */
    private List<Path> roots(BuildLayout layout) {
        List<Path> out = new ArrayList<>(PlannerKsp.outputRoots(PlannerKsp.kspOutBase(layout)));
        out.add(ModuleLayout.mainResourcesDir(moduleDir, compact));
        BuildBlock.Resources r = build.build().resources();
        for (String rel : r.dirs()) out.add(moduleDir.resolve(rel));
        for (String rel : r.filtered()) out.add(moduleDir.resolve(rel));
        for (var root : ModuleLayoutPlugins.pluginContributedRoots(moduleDir)) {
            if (root.resource()) out.add(moduleDir.resolve(root.relative()));
        }
        List<Path> present = new ArrayList<>();
        for (Path dir : out) {
            Path normalized = dir.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized) && !present.contains(normalized)) present.add(normalized);
        }
        return present;
    }

    private static String slashed(Path p) {
        return p.toString().replace('\\', '/');
    }
}
