// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal local Giter8-style apply MVP): {@code default.properties} + {@code $key$} path and
 * content substitution under {@code src/main/g8} (or the template root when that tree is absent).
 *
 * <p>Not full Giter8 (no conditionals, includes, or git fetch). Remote templates and the engine
 * worker land in follow-ups — see {@code docs/features/giter8-templates.md}.
 */
public final class Giter8LocalApply {

    private static final Pattern TOKEN = Pattern.compile("\\$([A-Za-z0-9_.-]+)\\$");

    private Giter8LocalApply() {}

    /**
     * Apply a local template directory into {@code dest}. Returns the number of files written.
     *
     * @throws IOException if the template layout is invalid or IO fails
     */
    public static int apply(Path templateRoot, Path dest, Map<String, String> overrides) throws IOException {
        Path root = templateRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("template is not a directory: " + root);
        }
        Path g8 = root.resolve("src/main/g8");
        Path contentRoot = Files.isDirectory(g8) ? g8 : root;
        Path propsFile = Files.isRegularFile(root.resolve("default.properties"))
                ? root.resolve("default.properties")
                : contentRoot.resolve("default.properties");

        Map<String, String> props = new LinkedHashMap<>();
        if (Files.isRegularFile(propsFile)) {
            Properties p = new Properties();
            try (var in = Files.newInputStream(propsFile)) {
                p.load(in);
            }
            for (String name : p.stringPropertyNames()) {
                props.put(name, p.getProperty(name));
            }
        }
        if (overrides != null) props.putAll(overrides);
        // Giter8 convention: $name_normalized$ is the lowercase-hyphenated name
        // this used to assign name to itself, leaving the token unreplaced).
        if (props.containsKey("name")) {
            props.putIfAbsent("name_normalized", normalize(props.get("name")));
        }

        Files.createDirectories(dest);
        int[] count = {0};
        Files.walkFileTree(contentRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals("default.properties")) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = contentRoot.relativize(file).toString().replace('\\', '/');
                // Path tokens: package-like props use '/' (Giter8 packaged format approximation).
                String renderedRel = substitute(rel, props, true);
                Path out = dest.resolve(renderedRel);
                Files.createDirectories(out.getParent());
                byte[] raw = Files.readAllBytes(file);
                // Binary-ish: if null byte present, copy raw without token replace
                boolean binary = false;
                for (byte b : raw) {
                    if (b == 0) {
                        binary = true;
                        break;
                    }
                }
                if (binary) {
                    Files.write(out, raw);
                } else {
                    String text = new String(raw, StandardCharsets.UTF_8);
                    Files.writeString(out, substitute(text, props, false), StandardCharsets.UTF_8);
                }
                count[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    public static String substitute(String input, Map<String, String> props) {
        return substitute(input, props, false);
    }

    public static String substitute(String input, Map<String, String> props, boolean pathMode) {
        Matcher m = TOKEN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String val = props.getOrDefault(key, m.group(0));
            if (pathMode && props.containsKey(key) && ("package".equals(key) || key.endsWith(".package"))) {
                val = val.replace('.', '/');
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Giter8 name normalization: lowercase, runs of non-alphanumerics collapse to '-'. */
    public static String normalize(String name) {
        return name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    /** The template's own {@code default.properties} {@code name}, when present. */
    public static java.util.Optional<String> defaultName(Path templateRoot) {
        Path g8 = templateRoot.resolve("src/main/g8");
        Path contentRoot = Files.isDirectory(g8) ? g8 : templateRoot;
        for (Path propsFile :
                new Path[] {templateRoot.resolve("default.properties"), contentRoot.resolve("default.properties")}) {
            if (!Files.isRegularFile(propsFile)) continue;
            Properties p = new Properties();
            try (var in = Files.newInputStream(propsFile)) {
                p.load(in);
            } catch (IOException e) {
                return java.util.Optional.empty();
            }
            String n = p.getProperty("name");
            if (n != null && !n.isBlank()) return java.util.Optional.of(n.trim());
        }
        return java.util.Optional.empty();
    }
}
