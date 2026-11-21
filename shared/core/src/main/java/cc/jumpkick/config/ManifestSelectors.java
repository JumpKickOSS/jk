// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.version.VersionRanges;
import org.tomlj.TomlTable;

/** A version selector as {@code jk.toml} or a plugin table writes it, checked where it is written. */
public final class ManifestSelectors {

    private ManifestSelectors() {}

    /**
     * {@code selector}, the value of {@code key} in {@code table}. A range the resolver could not
     * read, such as {@code (1.0)} or {@code [2.0,1.0]}, is refused here, located at the key, rather
     * than at the next resolve with no line to point at.
     */
    public static VersionSelector checked(VersionSelector selector, TomlTable table, String key, String where) {
        if (!(selector instanceof VersionSelector.Range range)) return selector;
        try {
            VersionRanges.check(range.raw());
        } catch (IllegalArgumentException e) {
            throw JkBuildParseException.at(
                    table,
                    key,
                    where + ": malformed version range \"" + range.raw().trim() + "\"");
        }
        return selector;
    }
}
