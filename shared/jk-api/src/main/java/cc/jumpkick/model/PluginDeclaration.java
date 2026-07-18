// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Map;
import java.util.Objects;

/**
 * A third-party plugin from {@code [plugins]}: Maven {@code group}/{@code name}/{@code version}
 * plus remaining keys in {@link #config} (plain JDK types).
 */
public record PluginDeclaration(String alias, String group, String name, String version, Map<String, Object> config) {

    public PluginDeclaration {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        if (group.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': group must not be blank");
        if (name.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': name must not be blank");
        if (version.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': version must not be blank");
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    /** Maven {@code group:name} coordinate without version. */
    public String coordinate() {
        return group + ":" + name;
    }

    /** Maven {@code group:name:version} coordinate. */
    public String coordinateWithVersion() {
        return group + ":" + name + ":" + version;
    }
}
