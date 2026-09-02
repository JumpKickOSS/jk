// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Plugin-contributed source roots. Engine-only — pulls {@code JkBuildParser}. */
public final class ModuleLayoutPlugins {

    private ModuleLayoutPlugins() {}

    public static List<ModuleLayout.Root> pluginContributedRoots(Path moduleDir) {
        Path toml = moduleDir.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(toml)) return List.of();
        try {
            JkBuild build = JkBuildParser.parse(toml);
            List<ModuleLayout.Root> out = new ArrayList<>();
            for (PluginContributions.SourceRoot root : PluginContributions.sourceRoots(build, moduleDir)) {
                out.add(new ModuleLayout.Root(
                        root.dir(), root.resource() ? ModuleLayout.Kind.RESOURCE : ModuleLayout.Kind.SOURCE));
            }
            return List.copyOf(out);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
