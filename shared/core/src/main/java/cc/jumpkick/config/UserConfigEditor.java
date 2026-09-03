// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal merge editor for {@code ~/.jk/config.toml}. Preserves unknown keys; only
 * touches root-level {@code nerd-font}.
 */
public final class UserConfigEditor {

    /**
     * Matches any existing value form — bare boolean or quoted mode word — so re-running {@code jk
     * self setup-terminal} replaces rather than duplicates the key.
     */
    private static final Pattern TABLE_HEADER = Pattern.compile("(?m)^[ \\t]*\\[");

    private static final Pattern NERD_FONT_LINE =
            Pattern.compile("(?m)^([ \\t]*)nerd-font[ \\t]*=[ \\t]*(?:true|false|\"[^\"]*\"|'[^']*')[ \\t]*$");

    private UserConfigEditor() {}

    /**
     * Set root-level {@code nerd-font}, creating the file as needed. Returns the path written.
     */
    public static Path setNerdFont(Path configFile, NerdFontMode mode) throws IOException {
        Path parent = configFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        String text = Files.isRegularFile(configFile) ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
        String updated = upsertNerdFont(text, mode.toToml());
        Files.writeString(configFile, updated, StandardCharsets.UTF_8);
        return configFile;
    }

    /**
     * Pure text transform for tests. {@code value} is already TOML-encoded by {@link
     * NerdFontMode#toToml} and is a closed set of literals ({@code true}, {@code false},
     * {@code "auto"}, {@code "wedge"}, {@code "pill"}) — none contains a {@code $} or {@code \},
     * so it is safe to splice into a replacement without escaping.
     *
     * <p>Inserts at the top of the file when missing so the key stays root-level (TOML bare keys
     * after a {@code [table]} header would land under that table).
     */
    static String upsertNerdFont(String toml, String value) {
        if (toml == null) toml = "";
        // The reader is root-level only, so a copy under any [table] is one it never sees. Replace
        // the root copy in place; delete every table copy; insert at the top when no root copy.
        int rootEnd = firstTableHeader(toml);
        List<int[]> matches = new ArrayList<>();
        Matcher m = NERD_FONT_LINE.matcher(toml);
        while (m.find()) matches.add(new int[] {m.start(), m.end(), m.group(1).length()});
        StringBuilder sb = new StringBuilder(toml);
        boolean replacedAtRoot = false;
        for (int i = matches.size() - 1; i >= 0; i--) {
            int[] at = matches.get(i);
            if (at[0] < rootEnd && !replacedAtRoot) {
                sb.replace(at[0], at[1], toml.substring(at[0], at[0] + at[2]) + "nerd-font = " + value);
                replacedAtRoot = true;
            } else {
                sb.delete(at[0], Math.min(sb.length(), at[1] + 1));
            }
        }
        if (replacedAtRoot) return sb.toString();
        String line = "nerd-font = " + value + "\n";
        String rest = sb.toString();
        if (rest.isBlank()) return line;
        return line + "\n" + rest.stripLeading();
    }

    /** Offset of the first {@code [table]} header, or the text length when there is none. */
    private static int firstTableHeader(String toml) {
        Matcher h = TABLE_HEADER.matcher(toml);
        return h.find() ? h.start() : toml.length();
    }
}
