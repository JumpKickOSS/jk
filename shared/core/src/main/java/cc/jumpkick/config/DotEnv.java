// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code.env} files family,so a project needs no {@code direnv},
 * {@code dotenv-cli}, or wrapper script to set its own variables.
 *
 * <h2>Dialect</h2>
 *
 * There is no {@code.env} specification and implementations disagree, so jk's is stated rather
 * than inferred:
 *
 * <ul>
 * <li>{@code KEY=value}, one per line; surrounding whitespace on both sides is trimmed
 * <li>{@code #} begins a comment; blank lines are ignored
 * <li>single or double quotes may wrap a value, and are removed
 * <li>escapes ({@code \\n}, {@code \\t}, {@code \\"}, {@code \\\\}) are honoured <b>only</b> inside
 * double quotes — single quotes are literal, as in the shell
 * <li>a leading {@code export } is tolerated and ignored, so a file can double as something you
 * {@code source}
 * <li><b>no variable expansion inside the file.</b> {@code BAR=${FOO}/x} stores that text
 * verbatim. The file stays dumb; expansion happens in {@code jk.toml}, at whitelisted
 * positions only, where it can be reasoned about
 * </ul>
 *
 * <h2>What this deliberately does not do</h2>
 *
 * It does not read the environment, decide precedence, or search directories — {@link EnvLookup}
 * owns that. This class only turns bytes into a map, which is what makes the dialect testable on
 * its own.
 */
public final class DotEnv {

    private DotEnv() {}

    /** Parse {@code file}; a missing file is an empty map, not an error. */
    public static Map<String, String> read(Path file) {
        try {
            if (!Files.isRegularFile(file)) return Map.of();
            return parse(Files.readAllLines(file));
        } catch (IOException e) {
            // An unreadable.env must not fail a build: it is supplementary configuration, and the
            // variables it would have set surface as "unset" where they are actually used.
            return Map.of();
        }
    }

    /** Parse already-read lines. Later assignments to the same key win, as in every shell. */
    public static Map<String, String> parse(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export "))
                line = line.substring("export ".length()).strip();
            int eq = line.indexOf('=');
            if (eq <= 0) continue; // no key, or no '=' at all — not an assignment
            String key = line.substring(0, eq).strip();
            if (!isValidKey(key)) continue;
            out.put(key, value(line.substring(eq + 1).strip()));
        }
        return out;
    }

    /**
     * Strip quotes and, for double-quoted values only, apply escapes. An unquoted value keeps a
     * trailing inline comment out: {@code FOO=bar # note} is {@code bar}, matching the shell and
     * every dotenv implementation.
     */
    private static String value(String raw) {
        if (raw.length() >= 2 && raw.charAt(0) == '\'' && raw.endsWith("'")) {
            return raw.substring(1, raw.length() - 1); // literal, no escapes
        }
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.endsWith("\"")) {
            return unescape(raw.substring(1, raw.length() - 1));
        }
        int hash = raw.indexOf(" #");
        return hash >= 0 ? raw.substring(0, hash).strip() : raw;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                default -> out.append('\\').append(next); // unknown escape stays as written
            }
        }
        return out.toString();
    }

    /** Environment names: a letter or underscore, then letters, digits, underscores. */
    private static boolean isValidKey(String key) {
        if (key.isEmpty()) return false;
        char first = key.charAt(0);
        if (!Character.isLetter(first) && first != '_') return false;
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }
}
