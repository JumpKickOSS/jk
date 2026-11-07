// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * {@code [plugin] min-java}: a module that declares a plugin's table must build for at least that
 * Java level. Checked once the module's {@code java} is concrete — after workspace inheritance —
 * so a member inheriting the root's level is judged by it.
 */
public final class PluginJavaFloor {

    private PluginJavaFloor() {}

    /**
     * Refuse {@code build} when a plugin whose table it declares needs a newer {@code java} than
     * the module builds for. A module that names no level builds for the engine JDK's default, and
     * is refused the same way.
     *
     * @throws JkBuildParseException naming the table, both levels and the line that fixes it
     */
    public static void check(JkBuild build, @Nullable Path moduleDir) {
        if (build.pluginConfigs().isEmpty()) return;
        int release = build.project().javaRelease();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            int floor = manifest.minJava();
            if (floor == 0 || !build.pluginConfigs().containsKey(manifest.id())) continue;
            if (release >= floor) continue;
            String level = release > 0 ? "builds for java " + release : "names no java level";
            throw new JkBuildParseException("[" + manifest.table() + "] needs java " + floor + " or later, and module `"
                    + build.project().name() + "` " + level + " — set java = " + floor + " in its jk.toml");
        }
    }
}
