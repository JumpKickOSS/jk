// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.release;

import cc.jumpkick.host.ReleasePlatform;
import java.util.List;
import java.util.Map;

/**
 * The {@code [release]} table: the tag template, the files that carry the declared version besides
 * {@code jk.toml} (each with the regular expression whose one group is the version), {@code
 * publish}, who uploads a release ({@code ci}, {@code local}, {@code none}, or empty to infer), and
 * {@code platforms}, the {@link ReleasePlatform} names whose platform-specific files a release must
 * carry (empty for this host's alone).
 */
public record ReleaseTable(String tag, Map<String, String> files, String publish, List<String> platforms) {

    /** The values {@code publish} and {@code jk release --publish} take. */
    public static final List<String> PUBLISH_MODES = List.of("ci", "local", "none");

    public ReleaseTable {
        files = Map.copyOf(files);
        platforms = List.copyOf(platforms);
    }

    /** No {@code [release]} table: the default tag, no extra files, publish mode inferred, this host. */
    public static final ReleaseTable DEFAULT = new ReleaseTable(ReleaseTag.DEFAULT_TEMPLATE, Map.of(), "", List.of());

    /** The platforms a release carries: those listed, else this host's. */
    public List<String> platformsOrHost() {
        return platforms.isEmpty() ? List.of(ReleasePlatform.current()) : platforms;
    }
}
