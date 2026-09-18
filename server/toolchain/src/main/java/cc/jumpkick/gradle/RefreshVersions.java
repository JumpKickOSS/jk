// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The pins refreshVersions keeps in {@code versions.properties} at a build's root while its scripts
 * spell every version as {@code _}. {@code version.<group>..<artifact>=x} names one coordinate;
 * {@code version.<key>=x} names a family under the short key the plugin's rules spell for it
 * ({@link RefreshVersionsRules}) — {@code kotlinx.coroutines} for {@code
 * org.jetbrains.kotlinx:kotlinx-coroutines-*}, {@code junit.jupiter} for {@code
 * org.junit.jupiter:junit-jupiter-*}; {@code plugin.<id>=x} names a Gradle plugin applied without
 * a version, the Kotlin plugins under {@code version.kotlin} and the Android ones under {@code
 * plugin.android}. A value that names another key ({@code version.kotlin}) is followed.
 */
final class RefreshVersions {

    /** The file refreshVersions writes beside the settings file. */
    static final String FILE = "versions.properties";

    private static final String VERSION_PREFIX = "version.";
    private static final String PLUGIN_PREFIX = "plugin.";

    /** How many times a value naming another key is followed, as the plugin allows. */
    private static final int REDIRECTS = 5;

    /**
     * A settings file's {@code extraArtifactVersionKeyRules}: a file beside the settings, or the
     * rules inline in a raw string.
     */
    private static final Pattern EXTRA_RULES = Pattern.compile(
            "extraArtifactVersionKeyRules\\s*\\(\\s*(?:file\\s*\\(\\s*[\"']([^\"'\\n]+)[\"']\\s*\\)|\"\"\"(.*?)\"\"\")",
            Pattern.DOTALL);

    /** No file: every lookup is a miss. */
    static final RefreshVersions NONE = new RefreshVersions(Map.of(), RefreshVersionsRules.bundled());

    /**
     * What the file says about one coordinate or plugin: its pin, and the key the plugin writes it
     * under — the key that was looked up, whether or not the file has it.
     */
    record Lookup(@Nullable String version, String key) {
        boolean found() {
            return version != null;
        }
    }

    /** Every {@code version.} and {@code plugin.} entry, by its full name. */
    private final Map<String, String> pins;

    private final RefreshVersionsRules rules;

    private RefreshVersions(Map<String, String> pins, RefreshVersionsRules rules) {
        this.pins = pins;
        this.rules = rules;
    }

    /**
     * The {@code versions.properties} of the build {@code projectDir} belongs to: the directory's
     * own, else the parent's when the directory is a subproject (no settings file of its own, one
     * beside its parent); {@link #NONE} when neither has one. The settings file beside it may add
     * rules of the build's own through {@code refreshVersions { extraArtifactVersionKeyRules(…) }}.
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
            if (!name.startsWith(VERSION_PREFIX) && !name.startsWith(PLUGIN_PREFIX)) continue;
            String value = props.getProperty(name).trim();
            if (!value.isEmpty()) pins.put(name, value);
        }
        Path dir = file.toAbsolutePath().getParent();
        return new RefreshVersions(pins, dir == null ? RefreshVersionsRules.bundled() : extraRules(dir));
    }

    /** The bundled rules plus those the settings file in {@code dir} adds, when it adds any. */
    private static RefreshVersionsRules extraRules(Path dir) throws IOException {
        RefreshVersionsRules rules = RefreshVersionsRules.bundled();
        for (String settings : GradleImporter.SETTINGS_FILES) {
            Path file = dir.resolve(settings);
            if (!Files.isRegularFile(file)) continue;
            Matcher m = EXTRA_RULES.matcher(Files.readString(file));
            while (m.find()) {
                String path = m.group(1);
                String text = path != null ? readIfThere(dir.resolve(path)) : m.group(2);
                if (text != null) rules = rules.plus(text);
            }
        }
        return rules;
    }

    private static @Nullable String readIfThere(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file) : null;
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
     * The pin for {@code group:artifact}: the exact key ({@code version.group..artifact}) when the
     * file has it, else the short key the rules spell for the coordinate — the lookup names the key
     * it read either way, so a miss can say which entry the file lacks.
     */
    Lookup lookup(String group, String artifact) {
        String exact = VERSION_PREFIX + group + ".." + artifact;
        String pinned = resolve(exact);
        if (pinned != null) return new Lookup(pinned, exact);
        String key = rules.keyFor(group, artifact).map(k -> VERSION_PREFIX + k).orElse(exact);
        return new Lookup(resolve(key), key);
    }

    /**
     * The pin for a Gradle plugin applied without a version: {@code version.kotlin} for a Kotlin
     * plugin, {@code plugin.android} for an Android one, {@code plugin.<id>} for the rest.
     */
    Lookup pluginVersion(String id) {
        String key = id.startsWith("org.jetbrains.kotlin.") || id.equals("org.jetbrains.kotlin")
                ? VERSION_PREFIX + "kotlin"
                : id.startsWith("com.android") ? PLUGIN_PREFIX + "android" : PLUGIN_PREFIX + id;
        return new Lookup(resolve(key), key);
    }

    /** The value of {@code key}, a value naming another key followed; null when the file has no such entry. */
    private @Nullable String resolve(String key) {
        String value = pins.get(key);
        for (int hops = 0; value != null && hops < REDIRECTS; hops++) {
            if (!isAlias(value)) return value;
            value = pins.get(value);
        }
        return value == null || isAlias(value) ? null : value;
    }

    private static boolean isAlias(String value) {
        return value.startsWith(VERSION_PREFIX) || value.startsWith(PLUGIN_PREFIX);
    }
}
