// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.release;

import java.util.Map;

/**
 * The {@code [release]} table: the tag template, and the files that carry the declared version
 * besides {@code jk.toml}, each with the regular expression whose one group is the version.
 */
public record ReleaseTable(String tag, Map<String, String> files) {

    public ReleaseTable {
        files = Map.copyOf(files);
    }

    /** No {@code [release]} table: the default tag, no extra files. */
    public static final ReleaseTable DEFAULT = new ReleaseTable(ReleaseTag.DEFAULT_TEMPLATE, Map.of());
}
