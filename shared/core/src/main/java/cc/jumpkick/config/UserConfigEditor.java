// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.util.FileLocks;
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
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Minimal merge editor for {@code ~/.jk/config.toml}. Preserves unknown keys; only touches the
 * root-level {@code nerd-font} and {@code self-update}, and appends credential-only {@code
 * [repositories.<id>]} tables.
 */
public final class UserConfigEditor {

    private static final Pattern TABLE_HEADER = Pattern.compile("(?m)^[ \\t]*\\[");

    private UserConfigEditor() {}

    /**
     * A credential-only {@code [repositories.<name>]} table: the credential this machine sends a
     * project's own repository {@code name} at {@code url}, never a repository of its own. {@code
     * credential} maps {@code token}, {@code username} or {@code password} to its value, a {@code
     * ${VAR}} reference for a secret.
     */
    public record CredentialTable(String name, URI url, Map<String, String> credential) {
        public CredentialTable {
            credential = Collections.unmodifiableMap(new LinkedHashMap<>(credential));
        }
    }

    /**
     * Append {@code table} to {@code configFile}, creating the file as needed. Answers false, and
     * leaves the file as it is, when it already declares a repository of that name.
     */
    public static boolean addCredential(Path configFile, CredentialTable table) throws IOException {
        return edit(
                configFile,
                text -> {
                    if (declaresRepository(text, table.name())) return null;
                    return appendCredential(text, table);
                },
                "added the credential for repository " + table.name());
    }

    /** {@code text} rewritten, or {@code null} to leave the file as it is. */
    @FunctionalInterface
    private interface Rewrite {
        @Nullable
        String apply(String text) throws IOException;
    }

    /**
     * Read, rewrite and replace {@code configFile} under its lock, so two editors never lose each
     * other's change, and by rename, so a reader (the engine loading credentials) never sees half a
     * file and a crash never truncates it. False when {@code rewrite} left it alone.
     */
    private static boolean edit(Path configFile, Rewrite rewrite, String summary) throws IOException {
        Path parent = configFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path lock = configFile.resolveSibling(configFile.getFileName() + ".lock");
        return FileLocks.withLock(lock, () -> {
            String text = Files.isRegularFile(configFile) ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
            String after = rewrite.apply(text);
            if (after == null) return false;
            AtomicWrites.replaceDurably(configFile, after);
            ChangeJournal.noteEdit(configFile, summary, text, after);
            return true;
        });
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

    /**
     * Whether {@code configFile} declares {@code table}'s repository at {@code table}'s URL, as an
     * earlier import wrote it or the user did; a trailing {@code /} is not a difference.
     */
    public static boolean declaresRepositoryAt(Path configFile, CredentialTable table) throws IOException {
        if (!Files.isRegularFile(configFile)) return false;
        TomlParseResult parsed = Toml.parse(Files.readString(configFile, StandardCharsets.UTF_8));
        if (parsed.hasErrors()) return false;
        TomlTable repositories = parsed.getTable("repositories");
        if (repositories == null) return false;
        Object declared = repositories.get(List.of(table.name()));
        String url = declared instanceof TomlTable t ? t.getString("url") : declared instanceof String u ? u : null;
        return url != null && stripSlash(url).equals(stripSlash(table.url().toString()));
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Pure text transform for tests: {@code toml} with {@code table} appended. */
    static String appendCredential(String toml, CredentialTable table) {
        StringBuilder sb = new StringBuilder(toml);
        if (!toml.isEmpty()) {
            if (!toml.endsWith("\n")) sb.append('\n');
            sb.append('\n');
        }
        sb.append("[repositories.").append(table.name()).append("]\n");
        sb.append("url = ").append(quote(table.url().toString())).append('\n');
        sb.append(RepositoryToml.CREDENTIAL_ONLY).append(" = true\n");
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
        edit(configFile, text -> upsertRoot(text, key, value), "set " + key + " = " + value);
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
