// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.GuardsConfig;
import java.util.Objects;
import java.util.Set;
import org.tomlj.TomlTable;

/**
 * The {@code [guards]} table of a {@code jk.toml} → a {@link GuardsConfig}. A table parser, not a
 * reader: the document comes from {@link JkBuildParser#guardsConfig(java.nio.file.Path)}, which owns
 * the disk read. Unknown keys are refused — a misspelt {@code on-build} that parsed cleanly would
 * leave the lanes on and the author believing them off.
 */
final class ManifestGuards {

    static final Set<String> KEYS = Set.of("on-build", "coverage-report");

    private ManifestGuards() {}

    static GuardsConfig parse(TomlTable root) {
        if (!root.contains("guards")) return GuardsConfig.ABSENT;
        if (!root.isTable("guards")) {
            throw new JkBuildParseException("`guards` must be a table — use [guards] with on-build / coverage-report");
        }
        TomlTable guards = Objects.requireNonNull(root.getTable("guards"), "guards");
        for (String k : guards.keySet()) {
            if (!KEYS.contains(k)) {
                throw new JkBuildParseException("[guards] unknown key `" + k + "` — expected on-build, coverage-report."
                        + " Rules live in jk-guards.toml, not in the manifest.");
            }
        }
        boolean onBuild = true;
        if (guards.contains("on-build")) {
            if (!guards.isBoolean("on-build")) {
                throw new JkBuildParseException("[guards] on-build must be a boolean");
            }
            onBuild = Boolean.TRUE.equals(guards.getBoolean("on-build"));
        }
        String coverage = null;
        if (guards.contains("coverage-report")) {
            if (!guards.isString("coverage-report")) {
                throw new JkBuildParseException("[guards] coverage-report must be a path string");
            }
            coverage = guards.getString("coverage-report");
        }
        return new GuardsConfig(onBuild, coverage, true);
    }
}
