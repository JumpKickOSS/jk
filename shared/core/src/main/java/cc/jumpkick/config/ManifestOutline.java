// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * A module's name and the workspace modules it depends on, read straight off its {@code jk.toml}
 * without the full parse: plugin-owned tables, catalogs and workspace inheritance are not checked,
 * so a process with no plugins installed (a graph worker) still reads every manifest.
 *
 * @param name the manifest's top-level {@code name}; {@code null} when it has none
 * @param workspaceDependencies keys of {@code workspace = true} entries in any {@code *dependencies} table
 */
public record ManifestOutline(@Nullable String name, Set<String> workspaceDependencies) {

    /** The outline of {@code file}; {@link IOException} when it cannot be read or is not TOML. */
    public static ManifestOutline read(Path file) throws IOException {
        TomlParseResult toml = Toml.parse(file);
        if (toml.hasErrors()) throw new IOException(file + ": " + toml.errors().getFirst());
        Set<String> deps = new TreeSet<>();
        for (String table : toml.keySet()) {
            if (!table.endsWith("dependencies") || !toml.isTable(table)) continue;
            TomlTable entries = toml.getTableOrEmpty(table);
            for (String key : entries.keySet()) {
                if (entries.isTable(key)
                        && Boolean.TRUE.equals(entries.getTableOrEmpty(key).getBoolean("workspace"))) {
                    deps.add(key);
                }
            }
        }
        return new ManifestOutline(toml.isString("name") ? toml.getString("name") : null, deps);
    }
}
