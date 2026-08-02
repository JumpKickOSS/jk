// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.config.JkTemplatesConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Giter8 short-name catalog and resolution (JK-1182 / JK-1380).
 *
 * <p>Resolution order for a short name (e.g. {@code java-cli}):
 *
 * <ol>
 *   <li>{@code $JK_TEMPLATES/&lt;name&gt;.g8} when the env var is set
 *   <li>{@code ~/.jk/templates/&lt;name&gt;.g8}
 *   <li>Walk up from cwd looking for {@code templates/&lt;name&gt;.g8} (dev checkout dogfood)
 *   <li>Configured third-party git sources ({@code [templates.sources]} in config.toml, plus any
 *       CLI {@code --template-source} extras)
 *   <li>Official monorepo {@code jkbuild/jk-templates} (overridable via {@code [templates]
 *       official})
 *   <li>Classpath resource tree {@code giter8/&lt;name&gt;/} bundled in the CLI jar
 * </ol>
 *
 * <p>Explicit git/HTTPS / {@code owner/repo} refs use {@link Giter8Git#fetch} directly (not this
 * class).
 */
public final class Giter8Catalog {

    /** Built-in short names for help text (not an exclusive allow-list for resolution). */
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
     * Resolve a short name using user config ({@link JkTemplatesConfig#resolve()}) and no extra CLI
     * sources.
     */
    public static Optional<Path> resolveShortName(String ref, Path cwd, Path extractRoot) throws IOException {
        return resolveShortName(ref, cwd, extractRoot, JkTemplatesConfig.resolve(), List.of());
    }

    /**
     * Resolve a short name with explicit config + optional one-shot CLI sources (git URLs / {@code
     * owner/repo}).
     *
     * @param extraSources git refs tried before the official monorepo (after config sources)
     */
    public static Optional<Path> resolveShortName(
            String ref,
            Path cwd,
            Path extractRoot,
            JkTemplatesConfig config,
            List<String> extraSources)
            throws IOException {
        if (!isShortName(ref)) return Optional.empty();
        String dirName = ref + ".g8";
        Path cache = Giter8Git.defaultCacheRoot();
        JkTemplatesConfig cfg = config == null ? JkTemplatesConfig.defaults() : config;
        List<String> extras = extraSources == null ? List.of() : extraSources;

        // 1) $JK_TEMPLATES
        String env = System.getenv("JK_TEMPLATES");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env).resolve(dirName);
            if (isTemplateRoot(p)) return Optional.of(p.toAbsolutePath().normalize());
            Path bare = Path.of(env).resolve(ref);
            if (isTemplateRoot(bare)) return Optional.of(bare.toAbsolutePath().normalize());
        }

        // 2) ~/.jk/templates/
        Path homeTemplates = Path.of(System.getProperty("user.home"), ".jk", "templates", dirName);
        if (isTemplateRoot(homeTemplates)) {
            return Optional.of(homeTemplates.toAbsolutePath().normalize());
        }

        // 3) Walk-up monorepo dogfood
        Path walk = cwd == null ? null : cwd.toAbsolutePath().normalize();
        for (int i = 0; i < 8 && walk != null; i++) {
            Path candidate = walk.resolve("templates").resolve(dirName);
            if (isTemplateRoot(candidate)) {
                return Optional.of(candidate);
            }
            walk = walk.getParent();
        }

        // 4) Configured third-party sources
        for (JkTemplatesConfig.Source src : cfg.sources()) {
            Optional<Path> hit = resolveFromGitSource(src.gitRef(), ref, cache);
            if (hit.isPresent()) return hit;
        }

        // 5) CLI one-shot sources (before official so users can override)
        for (String src : extras) {
            if (src == null || src.isBlank()) continue;
            Optional<Path> hit = resolveFromGitSource(src.strip(), ref, cache);
            if (hit.isPresent()) return hit;
        }

        // 6) Official monorepo
        Optional<Path> official = resolveFromGitSource(cfg.officialUrl(), ref, cache);
        if (official.isPresent()) return official;

        // 7) Classpath bootstrap
        if (extractRoot != null) {
            Optional<Path> fromCp = extractClasspathTemplate(ref, extractRoot.resolve(dirName));
            if (fromCp.isPresent()) return fromCp;
        }

        return Optional.empty();
    }

    /**
     * Clone (or reuse) a git source and find {@code shortName} inside it. Returns empty when git
     * fails or the name is absent (does not throw for missing name — caller may try next source).
     */
    static Optional<Path> resolveFromGitSource(String gitRef, String shortName, Path cacheRoot) {
        try {
            Path clone = Giter8Git.ensureClone(gitRef, cacheRoot);
            Optional<Path> nested = Giter8Git.findNamedTemplate(clone, shortName);
            if (nested.isPresent()) return nested;
            // Single-template repo: only match when the short name fits the template / URL.
            if (isTemplateRoot(clone) && singleTemplateMatches(clone, gitRef, shortName)) {
                return Optional.of(clone.toAbsolutePath().normalize());
            }
            return Optional.empty();
        } catch (IOException e) {
            // Offline / missing git / private 404 — try next source.
            return Optional.empty();
        }
    }

    /** True when a single-template clone is the intended target for {@code shortName}. */
    static boolean singleTemplateMatches(Path clone, String gitRef, String shortName) {
        Optional<String> propName = Giter8LocalApply.defaultName(clone);
        if (propName.isPresent() && shortName.equalsIgnoreCase(propName.get())) return true;
        String ref = gitRef == null ? "" : gitRef.toLowerCase(java.util.Locale.ROOT);
        String sn = shortName.toLowerCase(java.util.Locale.ROOT);
        return ref.contains("/" + sn) || ref.contains("/" + sn + ".g8") || ref.contains("/" + sn + ".git");
    }

    /** Human-readable list of known short names + configured source names for error messages. */
    public static String helpKnown(JkTemplatesConfig config) {
        List<String> parts = new ArrayList<>();
        parts.add("built-in: " + String.join(", ", DESCRIPTIONS.keySet()));
        parts.add("official: " + (config == null ? JkTemplatesConfig.DEFAULT_OFFICIAL : config.officialUrl()));
        if (config != null && !config.sources().isEmpty()) {
            List<String> names = config.sources().stream().map(JkTemplatesConfig.Source::name).toList();
            parts.add("config sources: " + String.join(", ", names));
        }
        return String.join("; ", parts);
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
