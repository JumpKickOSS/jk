// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.release;

import java.util.List;
import java.util.Map;

/**
 * The {@code [release]} table: the tag template, the files that carry the declared version besides
 * {@code jk.toml} (each with the regular expression whose one group is the version), and
 * {@code publish}, who uploads a release: {@code ci}, {@code local}, {@code none}, or empty to infer.
 */
public record ReleaseTable(String tag, Map<String, String> files, String publish) {

    /** The values {@code publish} and {@code jk release --publish} take. */
    public static final List<String> PUBLISH_MODES = List.of("ci", "local", "none");

    public ReleaseTable {
        files = Map.copyOf(files);
    }

    /** No {@code [release]} table: the default tag, no extra files, publish mode inferred. */
    public static final ReleaseTable DEFAULT = new ReleaseTable(ReleaseTag.DEFAULT_TEMPLATE, Map.of(), "");
}
