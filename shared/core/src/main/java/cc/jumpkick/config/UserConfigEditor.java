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
 * touches {@code [global].nerdfont}.
 */
public final class UserConfigEditor {

    private static final Pattern NERDFONT_LINE =
            Pattern.compile("(?m)^([ \\t]*)nerdfont[ \\t]*=[ \\t]*(true|false)[ \\t]*$");

    private UserConfigEditor() {}

    /**
     * Set {@code [global].nerdfont = true|false}, creating the file / {@code [global]} table as
     * needed. Returns the path written.
     */
    public static Path setNerdfont(Path configFile, boolean enabled) throws IOException {
        Path parent = configFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        String text = Files.isRegularFile(configFile) ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
        String value = enabled ? "true" : "false";
        String updated = upsertNerdfont(text, value);
        Files.writeString(configFile, updated, StandardCharsets.UTF_8);
        return configFile;
    }

    /** Pure text transform for tests. */
    static String upsertNerdfont(String toml, String trueOrFalse) {
        if (toml == null) toml = "";
        Matcher m = NERDFONT_LINE.matcher(toml);
        if (m.find()) {
            return m.replaceFirst(m.group(1) + "nerdfont = " + trueOrFalse);
        }
        if (toml.contains("[global]")) {
            // Insert after [global] header line
            return toml.replaceFirst("(?m)^(\\[global\\][^\\n]*\\n)", "$1nerdfont = " + trueOrFalse + "\n");
        }
        String block = "[global]\nnerdfont = " + trueOrFalse + "\n";
        if (toml.isBlank()) return block;
        return toml.stripTrailing() + "\n\n" + block;
    }
}
