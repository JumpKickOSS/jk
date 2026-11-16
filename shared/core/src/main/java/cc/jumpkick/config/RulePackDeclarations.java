// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;

/**
 * The rule packs a workspace's root rules file names under {@code [guards] extends}, as written.
 * Read here, below the guard module, so the lock's freshness check and the guard loader read one
 * list: a pack the rules file names and the lock does not pin is a stale lock, never a pack served
 * from whatever an earlier build unpacked.
 */
public final class RulePackDeclarations {

    /** The root rules file. */
    public static final String RULES_FILE = "jk-guards.toml";

    /**
     * Parsed {@code [guards] extends} per rules file, stamped on its content stamp: this runs for
     * every module of a workspace's cache check, and a stamp is meant to cost a stat.
     */
    private static final StampedMemo<Path, String, List<String>> DECLARED = StampedMemo.bounded(64);

    private RulePackDeclarations() {}

    /** The {@code [guards] extends} coordinates of {@code root}'s rules file, as written (unparsed). */
    public static List<String> of(Path root) throws IOException {
        Path file = root.resolve(RULES_FILE);
        String overlaid = ManifestOverlay.text(file);
        if (overlaid != null) return of(overlaid);
        if (!Files.isRegularFile(file)) return List.of();
        String hash = FileContentStamp.of(file);
        if (hash == null) return List.of();
        try {
            return DECLARED.get(file, hash, () -> {
                try {
                    return of(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException unreadable) {
                    throw new UncheckedIOException(unreadable);
                }
            });
        } catch (UncheckedIOException unreadable) {
            throw unreadable.getCause();
        }
    }

    /** The {@code [guards] extends} coordinates of a rules file's text; none when it does not parse. */
    public static List<String> of(String rulesText) {
        TomlParseResult toml = Toml.parse(rulesText);
        List<String> out = new ArrayList<>();
        if (toml.hasErrors()) return out;
        Object v = toml.get(List.of("guards", "extends"));
        if (v instanceof TomlArray a) for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
        else if (v instanceof String s) out.add(s);
        return out;
    }
}
