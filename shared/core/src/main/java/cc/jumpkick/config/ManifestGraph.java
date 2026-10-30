// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The {@code [graph]} table of a {@code jk.toml} → a {@link GraphConfig}. Unknown keys are refused,
 * so a misspelt {@code aliases} does not leave every alias silently unread.
 */
final class ManifestGraph {

    static final String TABLE = "graph";

    static final Set<String> KEYS = Set.of("aliases");

    private ManifestGraph() {}

    static GraphConfig parse(TomlTable root) {
        if (!root.contains(TABLE)) return GraphConfig.EMPTY;
        if (!root.isTable(TABLE)) throw new JkBuildParseException("`graph` must be a table — use [graph]");
        TomlTable graph = Objects.requireNonNull(root.getTable(TABLE), TABLE);
        for (String k : graph.keySet()) {
            if (!KEYS.contains(k)) {
                throw new JkBuildParseException("[graph] unknown key `" + k + "` — expected aliases");
            }
        }
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        if (graph.contains("aliases")) {
            if (!graph.isTable("aliases")) {
                throw new JkBuildParseException("[graph.aliases] must be a table: Term = [\"word\", ...]");
            }
            TomlTable table = Objects.requireNonNull(graph.getTable("aliases"), "aliases");
            for (String term : table.keySet()) {
                TomlArray words = table.isArray(List.of(term)) ? table.getArray(List.of(term)) : null;
                if (words == null) {
                    throw new JkBuildParseException("[graph.aliases] " + term + " must be a list of words");
                }
                List<String> out = new ArrayList<>();
                for (int i = 0; i < words.size(); i++) {
                    if (!(words.get(i) instanceof String w)) {
                        throw new JkBuildParseException("[graph.aliases] " + term + " must be a list of words");
                    }
                    if (!w.isBlank()) out.add(w.strip());
                }
                aliases.put(term, List.copyOf(out));
            }
        }
        return new GraphConfig(aliases);
    }
}
