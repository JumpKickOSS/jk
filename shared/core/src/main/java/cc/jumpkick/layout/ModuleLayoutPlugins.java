// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Plugin-contributed source roots. Engine-only — pulls {@code JkBuildParser} (JK-2151). */
public final class ModuleLayoutPlugins {

    private ModuleLayoutPlugins() {}

    public static List<ModuleLayout.Root> pluginContributedRoots(Path moduleDir) {
        Path toml = moduleDir.resolve("jk.toml");
        if (!Files.isRegularFile(toml)) return List.of();
        try {
            JkBuild build = JkBuildParser.parse(toml);
            List<ModuleLayout.Root> out = new ArrayList<>();
            for (cc.jumpkick.plugin.manifest.PluginContributions.SourceRoot root :
                    cc.jumpkick.plugin.manifest.PluginContributions.sourceRoots(build, moduleDir)) {
                out.add(new ModuleLayout.Root(
                        root.dir(), root.resource() ? ModuleLayout.Kind.RESOURCE : ModuleLayout.Kind.SOURCE));
            }
            return List.copyOf(out);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
