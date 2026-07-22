// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.DenyPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** Parses the {@code [deny]} block of a {@code jk.toml} into a {@link DenyPolicy}. */
public final class DenyPolicyParser {

    private DenyPolicyParser() {}

    public static DenyPolicy parse(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    public static DenyPolicy parse(String toml) {
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new JkBuildParseException(
                    "failed to parse jk.toml: " + result.errors().getFirst().getMessage());
        }
        TomlTable deny = result.getTable("deny");
        if (deny == null) return DenyPolicy.permissive();
        return parseDenyBlock(deny);
    }

    private static DenyPolicy parseDenyBlock(TomlTable deny) {
        List<String> sources = optionalStringList(deny.getTable("sources"), "deny");
        List<String> deniedLicenses = optionalStringList(deny.getTable("licenses"), "deny");
        List<String> allowedLicenses = optionalStringList(deny.getTable("licenses"), "allow");
        // JK-1062: licenses/yanked were parsed but never enforced — reject so configs cannot lie.
        if (!deniedLicenses.isEmpty() || !allowedLicenses.isEmpty()) {
            throw new JkBuildParseException(
                    "[deny.licenses] is not enforced yet (JK-1062). Remove the block until lock/audit "
                            + "enforcement ships — silent no-op is not allowed.");
        }
        String yankedRaw = deny.getString("yanked");
        DenyPolicy.YankedPolicy yanked;
        if (yankedRaw != null && !yankedRaw.isBlank()) {
            yanked = parseYanked(yankedRaw);
            if (yanked != DenyPolicy.YankedPolicy.ALLOW) {
                throw new JkBuildParseException("deny.yanked is not enforced yet (JK-1062). Omit deny.yanked, or set "
                        + "`yanked = \"allow\"` until enforcement ships — silent no-op is not allowed.");
            }
        } else {
            // Unenforced default when only [deny.sources] is present.
            yanked = DenyPolicy.YankedPolicy.ALLOW;
        }
        return new DenyPolicy(sources, List.of(), List.of(), yanked);
    }

    private static List<String> optionalStringList(TomlTable table, String key) {
        if (table == null) return List.of();
        TomlArray arr = table.getArray(key);
        if (arr == null) return List.of();
        List<String> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof String s)) {
                throw new JkBuildParseException("expected `deny." + key + "` to be a list of strings");
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
