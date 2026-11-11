// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The highlights of the releases an update moved across: what JumpKick says after it updated
 * itself. A release ships {@code jk-whats-new-<version>.md} (its own entry and the ones before it,
 * newest first, one {@code ## <version>} heading per release and one Markdown bullet per
 * highlight); installing it keeps that file as {@code whats-new.md} in the state directory, and
 * the web console and the terminal read it from there.
 */
public final class WhatsNew {

    /** The installed release's highlights, under the state directory. */
    public static final String FILE_NAME = "whats-new.md";

    /** Where every release's full notes are published. */
    public static final String RELEASES_URL = "https://github.com/JumpKickOSS/jk/releases/tag/v";

    /** One release's highlights: its version and its bullets as Markdown, continuation lines joined. */
    public record Entry(String version, List<String> bullets) {}

    private WhatsNew() {}

    /** The live file under the state root. */
    public static Path file() {
        return JkDirs.state().resolve(FILE_NAME);
    }

    /** The entries in {@code file}; empty when it is absent or unreadable. */
    public static List<Entry> read(Path file) {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Every {@code ## <version>} entry in {@code markdown}, in file order, with its bullets. */
    public static List<Entry> parse(String markdown) {
        List<Entry> entries = new ArrayList<>();
        String version = null;
        List<String> bullets = new ArrayList<>();
        StringBuilder bullet = null;
        for (String line : markdown.split("\\R")) {
            if (line.startsWith("## ")) {
                if (bullet != null) bullets.add(bullet.toString());
                if (version != null) entries.add(new Entry(version, List.copyOf(bullets)));
                version = line.substring(3).strip();
                bullets = new ArrayList<>();
                bullet = null;
            } else if (version != null && line.startsWith("- ")) {
                if (bullet != null) bullets.add(bullet.toString());
                bullet = new StringBuilder(line.substring(2).strip());
            } else if (bullet != null && !line.isBlank() && Character.isWhitespace(line.charAt(0))) {
                bullet.append(' ').append(line.strip());
            } else if (bullet != null) {
                bullets.add(bullet.toString());
                bullet = null;
            }
        }
        if (bullet != null) bullets.add(bullet.toString());
        if (version != null) entries.add(new Entry(version, List.copyOf(bullets)));
        return entries;
    }

    /**
     * The entries an update from {@code from} to {@code to} moved across: newer than {@code from},
     * no newer than {@code to}, newest first. Going back to an older release moves across none.
     */
    public static List<Entry> between(List<Entry> entries, String from, String to) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (Versions.compare(e.version(), from) > 0 && Versions.compare(e.version(), to) <= 0) out.add(e);
        }
        out.sort((a, b) -> Versions.compare(b.version(), a.version()));
        return out;
    }

    /** "JumpKick was updated to 0.51.0 (from 0.50.3)", or "went back to" for an older release. */
    public static String headline(String from, String to) {
        String verb = Versions.compare(to, from) >= 0 ? "was updated to " : "went back to ";
        return "JumpKick " + verb + to + " (from " + from + ")";
    }

    /** The full notes of {@code version} on GitHub. */
    public static String releaseUrl(String version) {
        return RELEASES_URL + version;
    }
}
