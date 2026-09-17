// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The directories a plugin step's {@code sibling:<key>} input names: for every workspace sibling
 * in the module's MAIN/EXPORT closure that declares the plugin's table, the sibling's value under
 * {@code key} — one directory or a list of them, the schema default when its table omits the key —
 * resolved against the sibling's own directory, in dependency order. A sibling without the table contributes nothing: it has no
 * sources of that kind to import.
 */
final class SiblingFiles {

    private SiblingFiles() {}

    /** The declared {@code sibling:} inputs of {@code inputs}, each with its directories. */
    static Map<String, List<Path>> forInputs(
            List<String> inputs, Path moduleDir, JkBuild project, PluginDescriptor plugin) throws IOException {
        Map<String, List<Path>> out = new LinkedHashMap<>();
        for (String input : inputs) {
            if (!input.startsWith("sibling:")) continue;
            String key = input.substring("sibling:".length());
            out.put(key, of(moduleDir, project, plugin, key));
        }
        return out;
    }

    static List<Path> of(Path moduleDir, JkBuild project, PluginDescriptor plugin, String key) throws IOException {
        Map<Path, JkBuild> siblings =
                WorkspaceClasspath.closureSiblings(moduleDir, project, Set.of(Scope.EXPORT, Scope.MAIN));
        PluginDescriptor.SchemaKey schemaKey = plugin.schema().get(key);
        @Nullable Object fallback = schemaKey == null ? null : schemaKey.normalizedDefault();
        List<Path> out = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> sibling : siblings.entrySet()) {
            Optional<PluginConfig> table = sibling.getValue().pluginConfig(plugin.id());
            if (table.isEmpty()) continue;
            Object value = table.get().values().getOrDefault(key, fallback);
            if (value instanceof String rel && !rel.isBlank())
                out.add(sibling.getKey().resolve(rel));
            if (value instanceof List<?> rels) {
                for (Object rel : rels) {
                    if (rel instanceof String s && !s.isBlank())
                        out.add(sibling.getKey().resolve(s));
                }
            }
        }
        return out;
    }
}
