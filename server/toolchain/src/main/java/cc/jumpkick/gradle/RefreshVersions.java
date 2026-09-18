// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The pins refreshVersions keeps in {@code versions.properties} at a build's root while its scripts
 * spell every version as {@code _}. {@code version.<group>..<artifact>=x} names one coordinate;
 * {@code version.<key>=x} names a family the plugin's rules gather under a short key — {@code
 * kotlinx.coroutines} for {@code org.jetbrains.kotlinx:kotlinx-coroutines-*}, {@code junit.jupiter}
 * for {@code org.junit.jupiter:junit-jupiter-*} — whose every segment is a word of the coordinate.
 */
final class RefreshVersions {

    /** The file refreshVersions writes beside the settings file. */
    static final String FILE = "versions.properties";

    private static final String VERSION_PREFIX = "version.";

    /** No file: every lookup is a miss. */
    static final RefreshVersions NONE = new RefreshVersions(Map.of());

    /**
     * What the file says about one coordinate: its pin and the key that carried it, or the keys
     * that each could have when two short keys fit equally, or nothing.
     */
    record Lookup(@Nullable String version, List<String> keys) {
        boolean found() {
            return version != null;
        }

        boolean ambiguous() {
            return version == null && keys.size() > 1;
        }
    }

    /** {@code version.} entries, the key without its prefix → the pin. */
    private final Map<String, String> pins;

    private RefreshVersions(Map<String, String> pins) {
        this.pins = pins;
    }

    /**
     * The {@code versions.properties} of the build {@code projectDir} belongs to: the directory's
     * own, else the parent's when the directory is a subproject (no settings file of its own, one
     * beside its parent); {@link #NONE} when neither has one.
     */
    static RefreshVersions beside(Path projectDir) throws IOException {
        Path own = projectDir.resolve(FILE);
        if (Files.isRegularFile(own)) return read(own);
        Path parent = projectDir.toAbsolutePath().getParent();
        if (parent != null && !hasSettings(projectDir) && hasSettings(parent)) {
            Path root = parent.resolve(FILE);
            if (Files.isRegularFile(root)) return read(root);
        }
        return NONE;
    }

    static RefreshVersions read(Path file) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        Map<String, String> pins = new LinkedHashMap<>();
        for (String name : new TreeSet<>(props.stringPropertyNames())) {
            if (!name.startsWith(VERSION_PREFIX)) continue;
            String value = props.getProperty(name).trim();
            if (!value.isEmpty()) pins.put(name.substring(VERSION_PREFIX.length()), value);
        }
        return new RefreshVersions(pins);
    }

    private static boolean hasSettings(Path dir) {
        for (String settings : GradleImporter.SETTINGS_FILES) {
            if (Files.isRegularFile(dir.resolve(settings))) return true;
        }
        return false;
    }

    boolean isEmpty() {
        return pins.isEmpty();
    }

    /**
     * The pin for {@code group:artifact}: the exact key ({@code group..artifact}) when the file has
     * it, else the short key with the most segments among those whose every segment is a word of
     * the coordinate — two fitting equally is an ambiguity naming both, none is a miss.
     */
    Lookup lookup(String group, String artifact) {
        String exact = group + ".." + artifact;
        String pinned = pins.get(exact);
        if (pinned != null) return new Lookup(pinned, List.of(exact));
        Set<String> words = words(group, artifact);
        List<String> best = new ArrayList<>();
        int bestSegments = 0;
        for (Map.Entry<String, String> e : pins.entrySet()) {
            String key = e.getKey();
            if (key.contains("..")) continue;
            String[] segments = key.toLowerCase(Locale.ROOT).split("\\.");
            if (segments.length < bestSegments || !words.containsAll(List.of(segments))) continue;
            if (segments.length > bestSegments) {
                best.clear();
                bestSegments = segments.length;
            }
            best.add(key);
        }
        if (best.size() == 1) return new Lookup(pins.get(best.getFirst()), best);
        return new Lookup(null, best);
    }

    /** The coordinate's words: the group's dot-separated parts and the artifact's dash- and dot-separated parts. */
    private static Set<String> words(String group, String artifact) {
        Set<String> words = new TreeSet<>();
        for (String part : (group + "." + artifact).toLowerCase(Locale.ROOT).split("[.\\-_]")) {
            if (!part.isEmpty()) words.add(part);
        }
        return words;
    }
}
