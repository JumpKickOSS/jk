// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A third-party plugin from {@code [plugins]}: either a Maven coordinate pin or a local path pin,
 * always with a content {@link #sha256()} (fail-closed; no unpinned remote load).
 *
 * <p>Path pins use synthetic {@code path:&lt;alias&gt;} coordinates for lock identity. Remaining
 * keys ride in {@link #config} as plain JDK types.
 */
public record PluginDeclaration(
        String alias,
        String group,
        String name,
        String version,
        /** Project-relative or absolute path to a jar; null for Maven pins. */
        @Nullable String path,
        /** Required content pin — lowercase hex SHA-256 of the jar bytes (no {@code sha256:} prefix). */
        String sha256,
        Map<String, Object> config) {

    /** Synthetic group for path-pinned plugins in the lockfile. */
    public static final String PATH_GROUP = "path";

    public PluginDeclaration {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sha256, "sha256");
        if (alias.isBlank()) throw new IllegalArgumentException("plugin alias must not be blank");
        if (group.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': group must not be blank");
        if (name.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': name must not be blank");
        if (version.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': version must not be blank");
        if (sha256.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': sha256 must not be blank");
        String hex = normalizeSha256(sha256);
        if (hex.length() != 64 || !hex.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException(
                    "plugin '" + alias + "': sha256 must be 64 hex chars (got length " + hex.length() + ")");
        }
        sha256 = hex;
        if (path != null && path.isBlank()) path = null;
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    /** Maven / path pin from identity fields only (tests). */
    public PluginDeclaration(String alias, String group, String name, String version, String sha256) {
        this(alias, group, name, version, null, sha256, Map.of());
    }

    /** Maven {@code group:name} coordinate without version. */
    public String coordinate() {
        return group + ":" + name;
    }

    /** Maven {@code group:name:version} (or {@code path:alias:local} for path pins). */
    public String coordinateWithVersion() {
        return group + ":" + name + ":" + version;
    }

    public boolean isPathPin() {
        return path != null;
    }

    public Optional<String> pathOpt() {
        return Optional.ofNullable(path);
    }

    /** Strip optional {@code sha256:} prefix and lowercase. */
    public static String normalizeSha256(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.regionMatches(true, 0, "sha256:", 0, 7)) s = s.substring(7).trim();
        return s.toLowerCase(Locale.ROOT);
    }
}
