// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import java.util.ArrayList;
import java.util.List;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * {@code exclude = ["g:a", "g:*"]} in a dependency's inline table: the coordinates pruned from that
 * dependency's subtree at lock time, Maven's {@code <exclusions>}. Each entry is {@code
 * group:artifact}, {@code group:*}, {@code *:artifact} or {@code *:*}. The key belongs to a Maven
 * coordinate or a workspace edge that resolves to one — a git or path source has no POM subtree to
 * prune — and a catalog one-liner has no table to carry it, so an exclusion needs the inline form.
 * On a {@code [managed-dependencies]} entry it prunes every edge onto the module.
 */
final class DependencyExclusions {

    static final String KEY = "exclude";

    private DependencyExclusions() {}

    static Dependency apply(Dependency dep, TomlTable entry, Scope scope, String name) {
        if (!entry.contains(KEY)) return dep;
        String displayPath = scope.tomlSection() + "." + name + "." + KEY;
        TomlArray array = entry.isArray(KEY) ? entry.getArray(KEY) : null;
        if (array == null) {
            throw new JkBuildParseException(displayPath + " must be an array of \"group:artifact\" strings");
        }
        if (dep.isGit() || dep.isPath() || dep.isFile()) {
            throw new JkBuildParseException(
                    displayPath + " applies to a Maven coordinate (got a git/path/file source)");
        }
        List<String> exclusions = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Object raw = array.get(i);
            if (!(raw instanceof String spelling) || spelling.isBlank()) {
                throw new JkBuildParseException(displayPath + " entries must be non-blank \"group:artifact\" strings");
            }
            try {
                exclusions.add(Dependency.exclusion(spelling.trim()));
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException(displayPath + ": " + e.getMessage());
            }
        }
        return dep.withExclusions(exclusions);
    }
}
