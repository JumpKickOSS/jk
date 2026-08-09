// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.tomlj.TomlTable;

/**
 * Machine-scoped Giter8 template sources from {@code ~/.config/jk/config.toml} ({@code [templates]}).
 *
 * <pre>{@code
 * [templates]
 * # Optional override of the official monorepo (default: jkbuild/jk-templates).
 * official = "https://github.com/jkbuild/jk-templates"
 *
 * # Third-party monorepos or single-template git roots (name → url or table).
 * [templates.sources]
 * acme = "https://github.com/acme/jk-g8"
 * corp = { url = "https://git.example/corp/jk-templates.git", rev = "main" }
 * }</pre>
 *
 * <p>Lenient: missing/malformed config never fails a command — falls back to the built-in official
 * URI and an empty third-party list. See JK-1380.
 */
public record JkTemplatesConfig(String officialUrl, List<Source> sources) {

    /** Default official first-party templates monorepo. */
    public static final String DEFAULT_OFFICIAL = "https://github.com/jkbuild/jk-templates";

    /**
     * A named third-party (or extra) git template source. {@code rev} is an optional branch/tag for
     * shallow clone ({@code git clone --branch}).
     */
    public record Source(String name, String url, Optional<String> rev) {
        public Source {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(rev, "rev");
            name = name.strip();
            url = url.strip();
        }

        /** Git ref string for clone ({@code url} or {@code url#rev}). */
        public String gitRef() {
            if (rev.isEmpty() || rev.get().isBlank()) return url;
            return url + "#" + rev.get().strip();
        }
    }

    public JkTemplatesConfig {
        Objects.requireNonNull(officialUrl, "officialUrl");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (officialUrl.isBlank()) officialUrl = DEFAULT_OFFICIAL;
    }

    public static JkTemplatesConfig defaults() {
        return new JkTemplatesConfig(DEFAULT_OFFICIAL, List.of());
    }

    /** Load from {@code ~/.config/jk/config.toml}. */
    public static JkTemplatesConfig resolve() {
        return resolve(JkDirs.userConfigFile());
    }

    /** As {@link #resolve()} against an explicit config path (tests). */
    public static JkTemplatesConfig resolve(Path userConfig) {
        return TomlValues.parse(userConfig).map(JkTemplatesConfig::fromTomlRoot).orElseGet(JkTemplatesConfig::defaults);
    }

    /** Parse from a root TOML document (tests / explicit file). */
    public static JkTemplatesConfig fromTomlRoot(TomlTable root) {
        if (root == null) return defaults();
        TomlTable templates = root.getTable("templates");
        if (templates == null) return defaults();

        String official = templates.getString("official");
        if (official == null || official.isBlank()) official = DEFAULT_OFFICIAL;

        List<Source> sources = new ArrayList<>();
        TomlTable sourcesTable = templates.getTable("sources");
        if (sourcesTable != null) {
            for (String name : sourcesTable.keySet()) {
                Object value = sourcesTable.get(name);
                try {
                    if (value instanceof String s && !s.isBlank()) {
                        sources.add(new Source(name, s, Optional.empty()));
                    } else if (value instanceof TomlTable t) {
                        String url = t.getString("url");
                        if (url == null || url.isBlank()) continue;
                        String rev = t.getString("rev");
                        if (rev == null || rev.isBlank()) rev = t.getString("branch");
                        sources.add(new Source(
                                name, url, rev == null || rev.isBlank() ? Optional.empty() : Optional.of(rev)));
                    }
                } catch (RuntimeException ignored) {
                    // lenient: skip malformed entry
                }
            }
        }
        return new JkTemplatesConfig(official.strip(), sources);
    }
}
