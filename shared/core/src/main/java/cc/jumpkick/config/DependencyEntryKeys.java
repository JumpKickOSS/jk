// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.List;
import org.tomlj.TomlTable;

/**
 * The keys a dependency inline table may carry, and the refusal of any other by name. A misspelt
 * {@code excludes} — or a key this engine does not know — must not parse cleanly and drop.
 */
final class DependencyEntryKeys {

    /** Every key of a {@code [<scope>-dependencies]} entry, git ref keys included. */
    static final List<String> DEPENDENCY = List.of(
            "group",
            "name",
            "version",
            "git",
            "path",
            "sha256",
            "workspace",
            "optional",
            "kind",
            "classifier",
            "fixtures",
            DependencyExclusions.KEY,
            "features",
            "default-features",
            "tag",
            "branch",
            "rev",
            "submodules",
            "verify-signed");

    /** Every key of a {@code [workspace.dependencies]} entry: a shared coordinate or git source. */
    static final List<String> WORKSPACE = List.of(
            "group",
            "name",
            "version",
            "git",
            "path",
            DependencyExclusions.KEY,
            "tag",
            "branch",
            "rev",
            "submodules",
            "verify-signed");

    private DependencyEntryKeys() {}

    /** Refuse the first key of {@code entry} outside {@code known}, naming the key and the entry. */
    static void requireKnown(TomlTable entry, String displayPath, List<String> known) {
        for (String key : entry.keySet()) {
            if (!known.contains(key)) {
                throw new JkBuildParseException(
                        displayPath + " unknown key `" + key + "` — expected one of: " + String.join(", ", known));
            }
        }
    }
}
