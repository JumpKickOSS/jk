// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * First-party Giter8 short-name catalog.
 *
 * <p>Resolution order for a short name (e.g. {@code quarkus}, {@code java-cli}):
 *
 * <ol>
 * <li>{@code $JK_TEMPLATES/&lt;name&gt;.g8} when the env var is set
 * <li>{@code ~/.jk/templates/&lt;name&gt;.g8}
 * <li>Walk up from cwd looking for {@code templates/&lt;name&gt;.g8} (dev checkout dogfood)
 * <li>Classpath resource tree {@code giter8/&lt;name&gt;/} bundled in the CLI jar
 * </ol>
 *
 * <p>Git/HTTPS remotes: {@link Giter8Git} (JK-1203).
 */
public final class Giter8Catalog {

    /** Short name → human intent (docs / help). */
    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

    static {
        DESCRIPTIONS.put("java-cli", "Simple Java 25 executable (Mill SIMPLE layout)");
        DESCRIPTIONS.put("kotlin-cli", "Simple Kotlin executable (Mill SIMPLE layout)");
        DESCRIPTIONS.put("quarkus", "Quarkus 3.x REST application ([quarkus] plugin)");
    }

    private Giter8Catalog() {}

    public static Map<String, String> descriptions() {
        return Map.copyOf(DESCRIPTIONS);
    }

    public static boolean isShortName(String ref) {
        return ref != null && ref.matches("[a-z][a-z0-9-]*") && !ref.contains("/") && !ref.contains(".");
    }

    /**
     * Resolve a template ref to a local directory containing {@code default.properties} /
     * {@code src/main/g8}. Returns empty when the ref is not a known short name or cannot be found.
     *
     * @param ref short name or (already failed) path stem
     * @param cwd current working directory (for upward {@code templates/} search)
     * @param extractRoot parent for classpath extraction (temp dir managed by caller)
     */
    public static Optional<Path> resolveShortName(String ref, Path cwd, Path extractRoot) throws IOException {
        if (!isShortName(ref) || !DESCRIPTIONS.containsKey(ref)) {
            return Optional.empty();
        }
        String dirName = ref + ".g8";

        String env = System.getenv("JK_TEMPLATES");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env).resolve(dirName);
            if (isTemplateRoot(p)) return Optional.of(p.toAbsolutePath().normalize());
        }

        Path homeTemplates = Path.of(System.getProperty("user.home"), ".jk", "templates", dirName);
        if (isTemplateRoot(homeTemplates)) {
            return Optional.of(homeTemplates.toAbsolutePath().normalize());
        }

        // Walk up from cwd (and its parents) for monorepo dogfood: …/jk/templates/<name>.g8
        Path walk = cwd.toAbsolutePath().normalize();
        for (int i = 0; i < 8 && walk != null; i++) {
            Path candidate = walk.resolve("templates").resolve(dirName);
            if (isTemplateRoot(candidate)) {
                return Optional.of(candidate);
            }
            walk = walk.getParent();
        }

        Optional<Path> fromCp = extractClasspathTemplate(ref, extractRoot.resolve(dirName));
        if (fromCp.isPresent()) return fromCp;

        return Optional.empty();
    }

    static boolean isTemplateRoot(Path p) {
        if (p == null || !Files.isDirectory(p)) return false;
        if (Files.isRegularFile(p.resolve("default.properties"))) return true;
        Path g8 = p.resolve("src/main/g8");
        return Files.isDirectory(g8)
                && (Files.isRegularFile(g8.resolve("default.properties"))
                        || Files.isRegularFile(p.resolve("default.properties")));
    }

    /**
     * Copy {@code classpath:/giter8/&lt;name&gt;/…} into {@code dest}. Returns empty when the
     * resource tree is absent.
     */
    static Optional<Path> extractClasspathTemplate(String name, Path dest) throws IOException {
        String prefix = "giter8/" + name;
        ClassLoader cl = Giter8Catalog.class.getClassLoader();
        URL marker = cl.getResource(prefix + "/default.properties");
        if (marker == null) return Optional.empty();

        try {
            String external = marker.toExternalForm();
            // jar:file:/path/to.jar!/giter8/name/default.properties
            int bang = external.indexOf('!');
            if (external.startsWith("jar:") && bang > 0) {
                URI jarUri = URI.create(external.substring(0, bang));
                String entryPath = external.substring(bang + 1); // /giter8/name/default.properties
                if (entryPath.startsWith("/")) entryPath = entryPath.substring(1);
                String rootEntry = prefix + "/";
                try (FileSystem fs = FileSystems.newFileSystem(jarUri, Collections.emptyMap())) {
                    Path root = fs.getPath(rootEntry);
                    if (!Files.isDirectory(root)) {
                        root = fs.getPath("/" + rootEntry);
                    }
                    if (!Files.isDirectory(root)) return Optional.empty();
                    copyTree(root, dest);
                    return isTemplateRoot(dest) ? Optional.of(dest) : Optional.empty();
                }
            }
            if ("file".equals(marker.getProtocol())) {
                Path props = Path.of(marker.toURI());
                Path root = props.getParent();
                if (root != null && Files.isDirectory(root)) {
                    copyTree(root, dest);
                    return isTemplateRoot(dest) ? Optional.of(dest) : Optional.empty();
                }
            }
        } catch (Exception e) {
            throw new IOException("failed to extract classpath template '" + name + "': " + e.getMessage(), e);
        }

        // Last resort: copy the single properties file (incomplete — treat as miss).
        try (InputStream in = cl.getResourceAsStream(prefix + "/default.properties")) {
            if (in == null) return Optional.empty();
            Files.createDirectories(dest);
            Files.copy(in, dest.resolve("default.properties"), StandardCopyOption.REPLACE_EXISTING);
        }
        return Optional.empty();
    }

    private static void copyTree(Path src, Path dest) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = src.relativize(p);
                Path out = dest.resolve(rel.toString().replace('\\', '/'));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
