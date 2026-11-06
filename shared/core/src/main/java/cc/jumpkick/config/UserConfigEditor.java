// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Minimal merge editor for {@code ~/.jk/config.toml}. Preserves unknown keys; only touches the
 * root-level {@code nerd-font} and {@code self-update}, and appends {@code [repositories.<id>]}
 * tables.
 */
public final class UserConfigEditor {

    private static final Pattern TABLE_HEADER = Pattern.compile("(?m)^[ \\t]*\\[");

    private UserConfigEditor() {}

    /**
     * A {@code [repositories.<name>]} table: {@code credential} maps {@code token}, {@code
     * username} or {@code password} to its value, a {@code ${VAR}} reference for a secret.
     */
    public record RepositoryTable(String name, URI url, List<String> groups, Map<String, String> credential) {
        public RepositoryTable {
            groups = List.copyOf(groups);
            credential = Collections.unmodifiableMap(new LinkedHashMap<>(credential));
        }
    }

    /**
     * Append {@code table} to {@code configFile}, creating the file as needed. Answers false, and
     * leaves the file as it is, when it already declares a repository of that name.
     */
    public static boolean addRepository(Path configFile, RepositoryTable table) throws IOException {
        String text = Files.isRegularFile(configFile) ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
        if (declaresRepository(text, table.name())) return false;
        Path parent = configFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(configFile, appendRepository(text, table), StandardCharsets.UTF_8);
        return true;
    }

    /**
     * Whether {@code toml} declares a repository named {@code name}. A file that does not parse is
     * refused rather than appended to, naming the file's own error.
     */
    static boolean declaresRepository(String toml, String name) throws IOException {
        TomlParseResult parsed = Toml.parse(toml);
        if (parsed.hasErrors()) {
            throw new IOException(
                    "~/.jk/config.toml does not parse: " + parsed.errors().getFirst());
        }
        TomlTable repositories = parsed.getTable("repositories");
        return repositories != null && repositories.keySet().contains(name);
    }

    /** Pure text transform for tests: {@code toml} with {@code table} appended. */
    static String appendRepository(String toml, RepositoryTable table) {
        StringBuilder sb = new StringBuilder(toml);
        if (!toml.isEmpty()) {
            if (!toml.endsWith("\n")) sb.append('\n');
            sb.append('\n');
        }
        sb.append("[repositories.").append(table.name()).append("]\n");
        sb.append("url = ").append(quote(table.url().toString())).append('\n');
        if (!table.groups().isEmpty()) {
            sb.append("groups = [");
            for (int i = 0; i < table.groups().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(quote(table.groups().get(i)));
            }
            sb.append("]\n");
        }
        table.credential()
                .forEach((k, v) -> sb.append(k).append(" = ").append(quote(v)).append('\n'));
        return sb.toString();
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * Set root-level {@code nerd-font}, creating the file as needed. Returns the path written.
     */
    public static Path setNerdFont(Path configFile, NerdFontMode mode) throws IOException {
        return setRoot(configFile, "nerd-font", mode.toToml());
    }

    /** Set root-level {@code self-update}, creating the file as needed. Returns the path written. */
    public static Path setSelfUpdate(Path configFile, SelfUpdatePolicy.Mode mode) throws IOException {
        return setRoot(configFile, SelfUpdatePolicy.KEY, "\"" + mode.spelling() + "\"");
    }

    private static Path setRoot(Path configFile, String key, String value) throws IOException {
        Path parent = configFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        String text = Files.isRegularFile(configFile) ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
        Files.writeString(configFile, upsertRoot(text, key, value), StandardCharsets.UTF_8);
        return configFile;
    }

    /**
     * Pure text transform for tests. {@code value} is already TOML-encoded and is one of a closed
     * set of literals (booleans or a quoted mode word), none containing {@code $} or {@code \}, so
     * it is safe to splice into a replacement without escaping.
     *
     * <p>Matches any existing value form, bare boolean or quoted word, so re-running replaces rather
     * than duplicates the key. Inserts at the top of the file when missing so the key stays
     * root-level (TOML bare keys after a {@code [table]} header would land under that table).
     */
    static String upsertRoot(String toml, String key, String value) {
        if (toml == null) toml = "";
        Pattern line = Pattern.compile(
                "(?m)^([ \\t]*)" + Pattern.quote(key) + "[ \\t]*=[ \\t]*(?:true|false|\"[^\"]*\"|'[^']*')[ \\t]*$");
        // The reader is root-level only, so a copy under any [table] is one it never sees. Replace
        // the root copy in place; delete every table copy; insert at the top when no root copy.
        int rootEnd = firstTableHeader(toml);
        List<int[]> matches = new ArrayList<>();
        Matcher m = line.matcher(toml);
        while (m.find()) matches.add(new int[] {m.start(), m.end(), m.group(1).length()});
        StringBuilder sb = new StringBuilder(toml);
        boolean replacedAtRoot = false;
        for (int i = matches.size() - 1; i >= 0; i--) {
            int[] at = matches.get(i);
            if (at[0] < rootEnd && !replacedAtRoot) {
                sb.replace(at[0], at[1], toml.substring(at[0], at[0] + at[2]) + key + " = " + value);
                replacedAtRoot = true;
            } else {
                sb.delete(at[0], Math.min(sb.length(), at[1] + 1));
            }
        }
        if (replacedAtRoot) return sb.toString();
        String added = key + " = " + value + "\n";
        String rest = sb.toString();
        if (rest.isBlank()) return added;
        return added + "\n" + rest.stripLeading();
    }

    /** Offset of the first {@code [table]} header, or the text length when there is none. */
    private static int firstTableHeader(String toml) {
        Matcher h = TABLE_HEADER.matcher(toml);
        return h.find() ? h.start() : toml.length();
    }
}
