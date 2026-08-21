// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Giter8 apply: {@code default.properties} + {@code src/main/g8} (or template root). Engine-only.
 *
 * <p>Does not wrap output in an extra {@code $name$} directory — {@code dest} is the project root.
 */
public final class Giter8Apply {

    private Giter8Apply() {}

    public static int apply(Path templateRoot, Path dest, Map<String, String> overrides) throws IOException {
        return apply(templateRoot, dest, overrides, null);
    }

    public static int apply(
            Path templateRoot, Path dest, Map<String, String> overrides, @Nullable MavenVersionLookup maven)
            throws IOException {
        Path root = templateRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("template is not a directory: " + root);
        }
        Path g8 = root.resolve("src/main/g8");
        Path contentRoot = Files.isDirectory(g8) ? g8 : root;
        Map<String, String> props = loadProperties(root, contentRoot, overrides, maven);
        List<Pattern> verbatim = verbatimPatterns(props.get("verbatim"));

        Files.createDirectories(dest);
        Path destReal = dest.toRealPath();
        int[] count = {0};
        Files.walkFileTree(contentRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Root-as-content templates (no src/main/g8): the clone's .git object tree and
                // template metadata are not project content — .git text files would even be
                // ST-rendered, aborting the apply on any stray '$' in a packed ref.
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (".git".equals(name)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                if (fileName.equals("default.properties") || fileName.equals(JkTemplateToml.FILE_NAME)) {
                    return FileVisitResult.CONTINUE;
                }
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = contentRoot.relativize(file).toString().replace('\\', '/');
                String renderedRel = Giter8Render.path(rel, props);
                if (renderedRel == null) {
                    return FileVisitResult.CONTINUE;
                }
                Path out = destReal.resolve(renderedRel).normalize();
                requireInside(destReal, out, renderedRel);
                Files.createDirectories(out.getParent());
                requireInside(destReal, out.getParent().toRealPath().resolve(out.getFileName()), renderedRel);
                byte[] raw = Files.readAllBytes(file);
                String text = matchesVerbatim(rel, file.getFileName().toString(), verbatim) ? null : decodeText(raw);
                if (text == null) {
                    Files.write(out, raw);
                } else {
                    Files.writeString(out, Giter8Render.content(text, props), StandardCharsets.UTF_8);
                }
                count[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    public static Optional<String> defaultName(Path templateRoot) {
        Path g8 = templateRoot.resolve("src/main/g8");
        Path contentRoot = Files.isDirectory(g8) ? g8 : templateRoot;
        for (Path propsFile :
                new Path[] {templateRoot.resolve("default.properties"), contentRoot.resolve("default.properties")}) {
            if (!Files.isRegularFile(propsFile)) continue;
            Properties p = new Properties();
            try (var in = Files.newInputStream(propsFile)) {
                p.load(in);
            } catch (IOException e) {
                return Optional.empty();
            }
            String n = p.getProperty("name");
            if (n != null && !n.isBlank()) return Optional.of(n.trim());
        }
        return Optional.empty();
    }

    static void requireInside(Path destReal, Path candidate, String renderedRel) throws IOException {
        if (!candidate.normalize().startsWith(destReal)) {
            throw new IOException("template entry escapes the project directory: " + renderedRel);
        }
    }

    static Map<String, String> loadProperties(
            Path root, Path contentRoot, Map<String, String> overrides, @Nullable MavenVersionLookup maven)
            throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        Path propsFile = Files.isRegularFile(root.resolve("default.properties"))
                ? root.resolve("default.properties")
                : contentRoot.resolve("default.properties");
        if (Files.isRegularFile(propsFile)) {
            Properties p = new Properties();
            try (var in = Files.newInputStream(propsFile)) {
                p.load(in);
            }
            for (String name : p.stringPropertyNames()) {
                props.put(name, p.getProperty(name));
            }
        }
        // Giter8 semantics: user input replaces defaults BEFORE derived properties expand, so
        // `package=$organization$.$name$`-style defaults derive from the overridden values. An
        // interpolation pass ahead of the overrides would freeze derivations to shipped defaults.
        if (overrides != null) props.putAll(overrides);
        props.putIfAbsent("simple", "no");
        for (Map.Entry<String, String> e : new ArrayList<>(props.entrySet())) {
            if (Giter8Maven.isMavenExpr(e.getValue())) {
                props.put(e.getKey(), Giter8Maven.resolveExpr(e.getValue(), maven));
            }
        }
        interpolateProps(props);
        if (props.containsKey("name")) {
            props.putIfAbsent("name_normalized", Giter8Formats.normalize(props.get("name")));
        }
        return props;
    }

    private static void interpolateProps(Map<String, String> props) throws IOException {
        for (int round = 0; round < 12; round++) {
            boolean changed = false;
            for (Map.Entry<String, String> e : new ArrayList<>(props.entrySet())) {
                String v = e.getValue();
                if (v == null || !v.contains("$")) continue;
                String next = Giter8Render.content(v, props);
                if (!next.equals(v)) {
                    props.put(e.getKey(), next);
                    changed = true;
                }
            }
            if (!changed) return;
        }
    }

    /**
     * The file as text iff it is valid UTF-8 with no NUL — anything else copies through
     * byte-for-byte. A lossy decode here silently corrupted non-UTF-8 text (ISO-8859-1 READMEs)
     * with replacement characters in every generated project.
     */
    private static @Nullable String decodeText(byte[] raw) {
        for (byte b : raw) {
            if (b == 0) return null;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException notUtf8) {
            return null;
        }
    }

    private static List<Pattern> verbatimPatterns(@Nullable String verbatim) {
        if (verbatim == null || verbatim.isBlank()) return List.of();
        List<Pattern> out = new ArrayList<>();
        for (String raw : verbatim.strip().split("\\s+")) {
            if (raw.isEmpty()) continue;
            out.add(glob(raw));
        }
        return List.copyOf(out);
    }

    private static Pattern glob(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) sb.append('\\');
                    sb.append(c);
                }
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static boolean matchesVerbatim(String rel, String fileName, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(fileName).matches() || p.matcher(rel).matches()) return true;
        }
        return false;
    }
}
