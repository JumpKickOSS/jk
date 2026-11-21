// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.TomlTableReader.optionalArray;
import static cc.jumpkick.config.TomlTableReader.optionalString;
import static cc.jumpkick.config.TomlTableReader.optionalTable;
import static cc.jumpkick.config.TomlTableReader.rejectUnknownKeys;

import cc.jumpkick.model.DenyPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The {@code [deny]} table of a {@code jk.toml} → a {@link DenyPolicy}. A table parser, not a
 * reader: the document comes from {@link JkBuildParser#denyPolicy(java.nio.file.Path)}, which owns
 * the disk read, the syntax-error message and {@link Interpolation#guard}.
 */
final class ManifestDeny {

    static final List<String> KEYS = List.of("sources", "licenses", "yanked");

    static final List<String> SOURCES_KEYS = List.of("deny");

    static final List<String> LICENSES_KEYS = List.of("deny", "allow");

    private ManifestDeny() {}

    static DenyPolicy parse(TomlTable root) {
        TomlTable deny = optionalTable(root, "deny", "`deny`");
        if (deny == null) return DenyPolicy.permissive();
        rejectUnknownKeys(deny, KEYS, "[deny]");
        TomlTable sourcesTable = optionalTable(deny, "sources", "deny.sources");
        if (sourcesTable != null) rejectUnknownKeys(sourcesTable, SOURCES_KEYS, "[deny.sources]");
        TomlTable licensesTable = optionalTable(deny, "licenses", "deny.licenses");
        if (licensesTable != null) rejectUnknownKeys(licensesTable, LICENSES_KEYS, "[deny.licenses]");
        List<String> sources = optionalStringList(sourcesTable, "deny", "[deny.sources] deny");
        List<String> deniedLicenses = optionalStringList(licensesTable, "deny", "[deny.licenses] deny");
        List<String> allowedLicenses = optionalStringList(licensesTable, "allow", "[deny.licenses] allow");
        String yankedRaw = optionalString(deny, "yanked", "deny.yanked");
        DenyPolicy.YankedPolicy yanked;
        if (yankedRaw != null && !yankedRaw.isBlank()) {
            yanked = parseYanked(yankedRaw);
            if (yanked != DenyPolicy.YankedPolicy.ALLOW) {
                throw new JkBuildParseException(
                        "deny.yanked is not supported yet; remove it or set `yanked = \"allow\"`");
            }
        } else {
            // Unenforced default when only [deny.sources] is present.
            yanked = DenyPolicy.YankedPolicy.ALLOW;
        }
        return new DenyPolicy(sources, deniedLicenses, allowedLicenses, yanked);
    }

    private static List<String> optionalStringList(@Nullable TomlTable table, String key, String where) {
        if (table == null) return List.of();
        TomlArray arr = optionalArray(table, key, where);
        if (arr == null) return List.of();
        List<String> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof String s)) {
                throw new JkBuildParseException(where + " must be a list of strings");
            }
            result.add(s);
        }
        return List.copyOf(result);
    }

    private static DenyPolicy.YankedPolicy parseYanked(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "deny" -> DenyPolicy.YankedPolicy.DENY;
            case "warn" -> DenyPolicy.YankedPolicy.WARN;
            case "allow" -> DenyPolicy.YankedPolicy.ALLOW;
            default ->
                throw new JkBuildParseException("deny.yanked must be `deny`, `warn`, or `allow` (got: " + raw + ")");
        };
    }
}
