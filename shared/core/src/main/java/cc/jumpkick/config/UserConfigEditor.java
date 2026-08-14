// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal merge editor for {@code ~/.config/jk/config.toml}. Preserves unknown keys; only
 * touches {@code [global].nerd-font}.
 */
public final class UserConfigEditor {

    /**
     * Matches any existing value form — bare boolean or quoted mode word — so re-running {@code jk
     * self setup-terminal} replaces rather than duplicates the key.
     */
    private static final Pattern NERD_FONT_LINE =
            Pattern.compile("(?m)^([ \\t]*)nerd-font[ \\t]*=[ \\t]*(?:true|false|\"[^\"]*\"|'[^']*')[ \\t]*$");

    private UserConfigEditor() {}

    /**
     * Set {@code [global].nerd-font}, creating the file / {@code [global]} table as needed. Returns
     * the path written.
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
     */
    static String upsertNerdFont(String toml, String value) {
        if (toml == null) toml = "";
        Matcher m = NERD_FONT_LINE.matcher(toml);
        if (m.find()) {
            return m.replaceFirst(m.group(1) + "nerd-font = " + value);
        }
        if (toml.contains("[global]")) {
            // Insert after [global] header line
            return toml.replaceFirst("(?m)^(\\[global\\][^\\n]*\\n)", "$1nerd-font = " + value + "\n");
        }
        String block = "[global]\nnerd-font = " + value + "\n";
        if (toml.isBlank()) return block;
        return toml.stripTrailing() + "\n\n" + block;
    }
}
