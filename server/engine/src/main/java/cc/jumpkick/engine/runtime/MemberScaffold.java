// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A template rendered as a module of an existing workspace takes the member form: a template writes
 * a standalone project, and what a standalone project sets for itself is the workspace root's in a
 * member. The identity a member inherits ({@code group}, {@code version}, {@code java}) is left to
 * the root where the root names it and the caller sent nothing else ({@code -p java=17}); and so
 * are the tables the root owns, and the {@code [image]} base (a workspace fact, whose JRE tag must
 * follow the level the module builds for): the test suites (a member may not set a suite's
 * tags), {@code [format]} and the rules file. What the template adds beyond that (its name,
 * dependencies, {@code [application]}, a framework's table, {@code [nullness]}) stays as written.
 */
final class MemberScaffold {

    private MemberScaffold() {}

    /** Top-level keys a member inherits from its workspace root. */
    private static final Set<String> INHERITED_KEYS = Set.of("group", "version", "java");

    /** The rules file: the root's, which a member file may only tighten. */
    private static final String RULES_FILE = "jk-guards.toml";

    /**
     * Rewrite the scaffold at {@code target} into the member form. {@code answered} are the template
     * parameters the caller sent and {@code root} the identity keys its workspace root names ({@link
     * #rootIdentity}): a key is left to the root when the root names it and the caller sent nothing
     * else for it. A key the root does not name stays as the template wrote it, so a module of a
     * workspace that names no {@code java} keeps the template's level rather than whatever JDK the
     * machine defaults to.
     */
    static void apply(Path target, Map<String, String> answered, Map<String, String> root) throws IOException {
        // The scaffold has just written its jk.toml, so this is that file.
        Path manifest = ManifestPaths.manifestIn(target);
        String toml = Files.readString(manifest, StandardCharsets.UTF_8);
        Set<String> dropped = new HashSet<>();
        for (String key : INHERITED_KEYS) {
            String named = root.get(key);
            String sent = answered.get(key);
            if (named != null && (sent == null || sent.strip().equals(named))) dropped.add(key);
        }
        String member = memberManifest(toml, dropped);
        if (!member.equals(toml)) AtomicWrites.replace(manifest, member);
        Files.deleteIfExists(target.resolve(RULES_FILE));
    }

    /**
     * The {@code group}, {@code version} and {@code java} the workspace root the scaffold at {@code
     * target} joins names, unquoted, as written; empty when the nearest {@code jk.toml} above it is
     * not a workspace root. The nearest one, as {@code jk new} finds the project it adds a module to:
     * the module is not listed yet, and an empty {@code modules} list is still that root's.
     */
    static Map<String, String> rootIdentity(Path target) throws IOException {
        Path manifest = null;
        for (Path dir = target.toAbsolutePath().normalize().getParent(); dir != null; dir = dir.getParent()) {
            Path candidate = ManifestPaths.manifestIn(dir);
            if (Files.isRegularFile(candidate)) {
                manifest = candidate;
                break;
            }
        }
        if (manifest == null) return Map.of();
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.stream()
                .noneMatch(
                        l -> header(l.strip()).equals("workspace") && l.strip().startsWith("["))) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (String line : lines) {
            String t = line.strip();
            if (t.startsWith("[")) break;
            String key = key(t);
            if (!INHERITED_KEYS.contains(key)) continue;
            String value = t.substring(t.indexOf('=') + 1);
            int hash = value.indexOf('#');
            if (hash >= 0) value = value.substring(0, hash);
            value = value.strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty()) out.put(key, value);
        }
        return out;
    }

    /**
     * {@code toml} without the inherited top-level keys and the root-owned tables, each table taken
     * with the comment block written just above its header. Runs of blank lines left behind are
     * collapsed to one.
     */
    static String memberManifest(String toml) {
        return memberManifest(toml, INHERITED_KEYS);
    }

    /** As {@link #memberManifest(String)}, dropping only the top-level keys in {@code dropped}. */
    static String memberManifest(String toml, Set<String> dropped) {
        List<String> lines = toml.lines().toList();
        List<String> out = new ArrayList<>();
        boolean skipping = false;
        boolean inTable = false;
        String table = "";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (trimmed.startsWith("[")) {
                inTable = true;
                table = header(trimmed);
                skipping = rootOwned(table);
                if (skipping) dropTrailingComments(out);
                if (!skipping) out.add(line);
                continue;
            }
            if (skipping) {
                // A comment block right above the next header belongs to that table; keep it for it.
                if (trimmed.startsWith("#") && nextHeaderFollows(lines, i)) {
                    skipping = false;
                    out.add(line);
                }
                continue;
            }
            if (!inTable && dropped.contains(key(trimmed))) continue;
            // The base image is a workspace fact, and the template's names a JRE of its own level:
            // left out, the root's base applies, else the default that follows the module's java.
            if (table.equals("image") && key(trimmed).equals("base")) continue;
            out.add(line);
        }
        return collapseBlanks(out);
    }

    private static boolean rootOwned(String header) {
        return header.equals("format") || header.equals("test.suites") || header.startsWith("test.suites.");
    }

    /** {@code [a.b]} or {@code [[a.b]]} to {@code a.b}. */
    private static String header(String trimmed) {
        String h = trimmed;
        int hash = h.indexOf('#');
        if (hash >= 0) h = h.substring(0, hash).strip();
        while (h.startsWith("[")) h = h.substring(1);
        while (h.endsWith("]")) h = h.substring(0, h.length() - 1);
        return h.strip();
    }

    private static String key(String trimmed) {
        int eq = trimmed.indexOf('=');
        return eq < 0 ? "" : trimmed.substring(0, eq).strip();
    }

    /** Whether the comment run starting at {@code from} ends right at a table header. */
    private static boolean nextHeaderFollows(List<String> lines, int from) {
        for (int j = from; j < lines.size(); j++) {
            String t = lines.get(j).strip();
            if (t.startsWith("#")) continue;
            return t.startsWith("[");
        }
        return false;
    }

    /** Drop the comment lines that end {@code out}: they introduced the table being dropped. */
    private static void dropTrailingComments(List<String> out) {
        while (!out.isEmpty() && out.getLast().strip().startsWith("#")) out.removeLast();
    }

    private static String collapseBlanks(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        boolean blank = false;
        for (String line : lines) {
            boolean isBlank = line.isBlank();
            if (isBlank && (blank || sb.isEmpty())) continue;
            sb.append(line).append('\n');
            blank = isBlank;
        }
        String s = sb.toString();
        while (s.endsWith("\n\n")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
