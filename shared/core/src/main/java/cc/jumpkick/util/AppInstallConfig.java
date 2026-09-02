// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-app install metadata under the config root: {@code <home>/config/<bin>/config.toml}
 * ({@code ~/.jk/config/<bin>/config.toml}). The global {@code config.toml} is not in here — it
 * sits at the home root.
 *
 * <p>Values are string TOML keys. Writers merge into any existing file (new keys overwrite). Used by
 * {@code jk install} (template and/or {@code jk-config.*} properties). The live engine pointer is
 * {@code jk-engine.toml} beside the engine jar, not this file.
 */
public final class AppInstallConfig {

    public static final String PROP_PREFIX = "jk-config.";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9._-]+)}");
    private static final Pattern KEY_LINE =
            Pattern.compile("(?m)^([A-Za-z0-9._-]+)\\s*=\\s*(\"(?:\\\\.|[^\"])*\")\\s*$");

    private AppInstallConfig() {}

    /** {@code <configDir>/<bin>/config.toml}. */
    public static Path path(String binName) {
        return path(JkDirs.current(), binName);
    }

    public static Path path(JkDirs dirs, String binName) {
        Objects.requireNonNull(dirs, "dirs");
        String bin = requireBin(binName);
        return dirs.configDir().resolve(bin).resolve(ManifestPaths.CONFIG);
    }

    /** Read string keys from the install config; empty map when missing/unreadable. */
    public static Map<String, String> read(String binName) {
        return read(JkDirs.current(), binName);
    }

    public static Map<String, String> read(JkDirs dirs, String binName) {
        Path file = path(dirs, binName);
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Map.of();
        }
    }

    public static Optional<String> get(String binName, String key) {
        String v = read(binName).get(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    /**
     * Merge {@code keys} into {@code <config>/<bin>/config.toml}. Existing keys not present in
     * {@code keys} are preserved. Empty values remove a key.
     */
    public static Path write(String binName, Map<String, String> keys) throws IOException {
        return write(JkDirs.current(), binName, keys);
    }

    public static Path write(JkDirs dirs, String binName, Map<String, String> keys) throws IOException {
        Objects.requireNonNull(keys, "keys");
        Path file = path(dirs, binName);
        Map<String, String> merged = new LinkedHashMap<>(read(dirs, binName));
        for (Map.Entry<String, String> e : keys.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            if (e.getValue() == null || e.getValue().isBlank()) merged.remove(e.getKey());
            else merged.put(e.getKey(), e.getValue());
        }
        AtomicWrites.replace(file, render(merged));
        return file;
    }

    /**
     * Render {@code template} with {@code ${key}} replacements from {@code values}, then merge the
     * resulting string keys (plus any leftover {@code values} not already in the template output)
     * into the install config.
     */
    public static Path writeTemplate(JkDirs dirs, String binName, String template, Map<String, String> values)
            throws IOException {
        Objects.requireNonNull(template, "template");
        Map<String, String> vars = values == null ? Map.of() : values;
        String rendered = substitute(template, vars);
        Map<String, String> fromTemplate = parse(rendered);
        Map<String, String> merged = new LinkedHashMap<>(vars);
        merged.putAll(fromTemplate);
        return write(dirs, binName, merged);
    }

    /** System properties whose names start with {@code jk-config.} (prefix stripped). */
    public static Map<String, String> jkConfigProperties() {
        return jkConfigProperties(System.getProperties());
    }

    public static Map<String, String> jkConfigProperties(Properties props) {
        Map<String, String> out = new LinkedHashMap<>();
        if (props == null) return out;
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(PROP_PREFIX)) continue;
            String key = name.substring(PROP_PREFIX.length());
            if (key.isBlank()) continue;
            String value = props.getProperty(name);
            if (value != null && !value.isBlank()) out.put(key, value);
        }
        return out;
    }

    /** Parse {@code key = "value"} lines from a TOML-lite body. */
    public static Map<String, String> parse(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return out;
        Matcher m = KEY_LINE.matcher(body);
        while (m.find()) {
            out.put(m.group(1), MinimalToml.unquote(m.group(2)));
        }
        return out;
    }

    /** Render string keys as {@code key = "value"} lines. */
    public static String render(Map<String, String> keys) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : keys.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) continue;
            sb.append(e.getKey())
                    .append(" = ")
                    .append(MinimalToml.quote(e.getValue()))
                    .append('\n');
        }
        return sb.toString();
    }

    static String substitute(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String rep = values.getOrDefault(key, m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String requireBin(String binName) {
        if (binName == null || binName.isBlank()) {
            throw new IllegalArgumentException("bin name is required");
        }
        if (binName.indexOf('/') >= 0 || binName.indexOf('\\') >= 0 || binName.contains("..")) {
            throw new IllegalArgumentException("invalid bin name: " + binName);
        }
        return binName;
    }
}
