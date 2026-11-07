// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.TestJvm;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The test-JVM defaults a module's plugins contribute, folded under the module's own settings. */
final class PluginTestDefaults {

    private PluginTestDefaults() {}

    /**
     * {@code build} with the {@code [contribute.test-system-properties]} of the module's plugins
     * folded under its own {@code [test]} settings: a key the module sets in {@code
     * system-properties}, or as a {@code -D} in {@code jvm-args}, keeps the module's value.
     */
    static BuildBlock fold(
            BuildBlock build, Map<String, PluginConfig> pluginConfigs, List<PluginDescriptor> installedManifests) {
        Map<String, String> contributed = PluginContributions.testSystemProperties(pluginConfigs, installedManifests);
        if (contributed.isEmpty()) return build;
        TestJvm declared = build.testJvm();
        Map<String, String> properties = new LinkedHashMap<>();
        contributed.forEach((key, value) -> {
            boolean set = declared.systemProperties().containsKey(key)
                    || declared.jvmArgs().stream()
                            .anyMatch(a -> a.equals("-D" + key) || a.startsWith("-D" + key + "="));
            if (!set) properties.put(key, value);
        });
        if (properties.isEmpty()) return build;
        properties.putAll(declared.systemProperties());
        return build.withTestJvm(new TestJvm(declared.jvmArgs(), properties));
    }
}
