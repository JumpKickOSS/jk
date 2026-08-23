// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.config.JkTemplatesConfig;
import cc.jumpkick.giter8.Giter8Apply;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Giter8 short-name catalog and resolution.
 *
 * <p>Resolution order for a short name (e.g. {@code java-cli}):
 *
 * <ol>
 *   <li>{@code $JK_TEMPLATES/&lt;name&gt;.g8} when the env var is set
 *   <li>{@link JkDirs#templates()} (cloned catalogs + user drop-ins)
 *   <li>Walk up from cwd looking for {@code templates/&lt;name&gt;.g8} (dev checkout dogfood)
 *   <li>Configured third-party git sources ({@code [templates.sources]} in config.toml, plus any
 *       CLI {@code --template-source} extras)
 *   <li>Official monorepo {@code JumpKickOSS/jk-templates} (overridable via {@code [templates]
 *       official} – shallow-cloned to the templates cache, preemptive on install)
 * </ol>
 *
 * <p>Explicit git/HTTPS / {@code owner/repo} refs use {@link Giter8Git#fetch} directly (not this
 * class).
 */
public final class Giter8Catalog {

    private Giter8Catalog() {}

    /** Built-in short names for help text (not an exclusive allow-list for resolution). */
    public static Map<String, String> descriptions() {
        return Map.of(
                "cli", "Simple executable",
                "cli-native", "Interactive Java CLI with JLine",
                "ktor-3", "Ktor service with Koin DI and Exposed/H2");
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
            String ref, Path cwd, Path extractRoot, JkTemplatesConfig config, List<String> extraSources)
            throws IOException {
        if (!isShortName(ref)) return Optional.empty();
        String dirName = ref + ".g8";
        Path cache = JkDirs.templates();
        JkTemplatesConfig cfg = config == null ? JkTemplatesConfig.defaults() : config;
        List<String> extras = extraSources == null ? List.of() : extraSources;

        // 1) $JK_TEMPLATES
        String env = System.getenv("JK_TEMPLATES");
        if (env != null && !env.isBlank()) {
            Optional<Path> hit = langKindOrFlat(Path.of(env), ref, dirName);
            if (hit.isPresent()) return hit;
        }

        // 2) store templates (user drop-ins at the catalog root)
        Optional<Path> stored = langKindOrFlat(cache, ref, dirName);
        if (stored.isPresent()) return stored;

        // 3) Walk-up monorepo dogfood
        Path walk = cwd == null ? null : cwd.toAbsolutePath().normalize();
        for (int i = 0; i < 8 && walk != null; i++) {
            Optional<Path> hit = langKindOrFlat(walk.resolve("templates"), ref, dirName);
            if (hit.isPresent()) return hit;
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
        Optional<String> propName = Giter8Apply.defaultName(clone);
        if (propName.isPresent() && shortName.equalsIgnoreCase(propName.get())) return true;
        String ref = gitRef == null ? "" : gitRef.toLowerCase(java.util.Locale.ROOT);
        String sn = shortName.toLowerCase(java.util.Locale.ROOT);
        return ref.contains("/" + sn) || ref.contains("/" + sn + ".g8") || ref.contains("/" + sn + ".git");
    }

    /** Human-readable list of known short names + configured source names for error messages. */
    public static String helpKnown(JkTemplatesConfig config) {
        List<String> parts = new ArrayList<>();
        parts.add("built-in: " + String.join(", ", descriptions().keySet()));
        parts.add("official: " + (config == null ? JkTemplatesConfig.DEFAULT_OFFICIAL : config.officialUrl()));
        if (config != null && !config.sources().isEmpty()) {
            List<String> names = config.sources().stream()
                    .map(JkTemplatesConfig.Source::name)
                    .toList();
            parts.add("config sources: " + String.join(", ", names));
        }
        return String.join("; ", parts);
    }

    private static Optional<Path> langKindOrFlat(Path root, String ref, String dirName) {
        if (root == null || !Files.isDirectory(root)) return Optional.empty();
        for (String lang : List.of("java", "kotlin", "groovy")) {
            Path none = root.resolve(lang).resolve("none").resolve(dirName);
            if (isTemplateRoot(none)) return Optional.of(none.toAbsolutePath().normalize());
            Path langDir = root.resolve(lang);
            if (!Files.isDirectory(langDir)) continue;
            try (var fws = Files.list(langDir)) {
                for (Path fw : fws.toList()) {
                    if (!Files.isDirectory(fw)) continue;
                    Path p = fw.resolve(dirName);
                    if (isTemplateRoot(p)) return Optional.of(p.toAbsolutePath().normalize());
                    Path bare = fw.resolve(ref);
                    if (isTemplateRoot(bare))
                        return Optional.of(bare.toAbsolutePath().normalize());
                }
            } catch (IOException ignored) {
                // try next lang
            }
        }
        return Optional.empty();
    }

    static boolean isTemplateRoot(Path p) {
        if (p == null || !Files.isDirectory(p)) return false;
        if (Files.isRegularFile(p.resolve("default.properties"))) return true;
        if (Files.isRegularFile(p.resolve(".jk-template.toml"))) return true;
        Path g8 = p.resolve("src/main/g8");
        return Files.isDirectory(g8)
                && (Files.isRegularFile(g8.resolve("default.properties"))
                        || Files.isRegularFile(p.resolve("default.properties")));
    }
}
