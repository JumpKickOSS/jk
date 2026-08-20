// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Builds the template picker list: official {@link Giter8ShortNames} catalog, then overlays / adds
 * entries discovered under local template roots by reading {@code default.properties} ({@code
 * jk_languages}, {@code jk_layout}, {@code name}).
 *
 * <p>Merge rules for a short name:
 *
 * <ol>
 *   <li>Start from the catalog row when the id is first-party
 *   <li>On-disk {@code jk_languages} / {@code jk_layout} win when present
 *   <li>Unknown short names from disk are appended (description from {@code name} or the id)
 *   <li>When layout is still unknown, infer from the template tree ({@link #inferLayout(Path)})
 * </ol>
 */
public final class Giter8TemplateIndex {

    private Giter8TemplateIndex() {}

    /**
     * Catalog-only index (no disk scan) — useful for tests and offline help text.
     */
    public static List<Giter8ShortNames.Entry> catalogOnly() {
        return Giter8ShortNames.entries();
    }

    /**
     * Picker row for HTTP/MCP/dashboard. Catalog entries have empty {@code kinds}; plugin
     * entries list kinds per language and win on id collision.
     */
    public record PickerRow(
            String id,
            String description,
            List<String> languages,
            String layout,
            boolean plugin,
            Map<String, List<String>> kinds) {
        public PickerRow {
            languages = languages == null ? List.of() : List.copyOf(languages);
            kinds = kinds == null || kinds.isEmpty() ? Map.of() : Map.copyOf(kinds);
        }
    }

    /** Catalog + local roots + installed plugin jars. */
    public static List<PickerRow> picker(List<Path> roots) {
        Map<String, PickerRow> byId = new LinkedHashMap<>();
        for (Giter8ShortNames.Entry e : build(roots)) {
            byId.put(e.id(), new PickerRow(e.id(), e.description(), e.languages(), e.layout(), false, Map.of()));
        }
        for (PluginTemplates.Installed p : PluginTemplates.installed()) {
            byId.put(
                    p.id(),
                    new PickerRow(
                            p.id(),
                            p.description(),
                            p.langs(),
                            Giter8ShortNames.LAYOUT_TRADITIONAL,
                            true,
                            p.kindsByLang()));
        }
        return List.copyOf(byId.values());
    }

    /**
     * Full picker list: official catalog, then scan each root for {@code *.g8} / bare short-name
     * dirs. Non-existent roots are skipped. Order: catalog order first, then newly discovered ids
     * in scan order.
     */
    public static List<Giter8ShortNames.Entry> build(List<Path> roots) {
        Map<String, Giter8ShortNames.Entry> byId = new LinkedHashMap<>();
        for (Giter8ShortNames.Entry e : Giter8ShortNames.entries()) {
            byId.put(e.id(), e);
        }
        Set<String> overlaid = new HashSet<>();
        if (roots != null) {
            for (Path root : roots) {
                if (root == null) continue;
                scanRoot(root.toAbsolutePath().normalize(), byId, overlaid);
            }
        }
        // Second pass: only ids pass 1 did NOT overlay get the (expensive) deep probe — the DFS
        // walks every root to depth 5, so re-probing already-merged ids is pure rework.
        if (roots != null && !roots.isEmpty()) {
            for (String id : idsNeedingProbe(byId.values(), overlaid)) {
                Path found = findTemplateDir(roots, id);
                if (found != null) {
                    byId.put(id, mergeFromDisk(byId.get(id), found));
                }
            }
        }
        return List.copyOf(byId.values());
    }

    /** Ids still catalog-only after pass 1 — the only ones worth a pass-2 deep probe. */
    static List<String> idsNeedingProbe(Collection<Giter8ShortNames.Entry> entries, Set<String> overlaid) {
        List<String> out = new ArrayList<>();
        for (Giter8ShortNames.Entry e : entries) {
            if (!overlaid.contains(e.id())) out.add(e.id());
        }
        return out;
    }

    /**
     * Default roots the engine / dashboard should scan (best-effort, no network):
     * {@code $JK_TEMPLATES}, {@code ~/.jk/templates}, official cache clones under {@code
     * ~/.jk/cache/templates}, and optional extra paths (e.g. monorepo {@code templates/}).
     */
    public static List<Path> defaultSearchRoots(Path home, List<Path> extras) {
        List<Path> roots = new ArrayList<>();
        String env = System.getenv("JK_TEMPLATES");
        if (env != null && !env.isBlank()) roots.add(Path.of(env));
        if (home != null) {
            roots.add(home.resolve(".jk").resolve("templates"));
            roots.add(home.resolve(".jk").resolve("cache").resolve("templates"));
            // XDG-style secondary cache
            String xdg = System.getenv("XDG_CACHE_HOME");
            if (xdg != null && !xdg.isBlank()) {
                roots.add(Path.of(xdg).resolve("jk").resolve("templates"));
            }
        }
        if (extras != null) {
            for (Path p : extras) {
                if (p != null) roots.add(p);
            }
        }
        return roots;
    }

    static void scanRoot(Path root, Map<String, Giter8ShortNames.Entry> byId, Set<String> overlaid) {
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) continue;
                String fileName = child.getFileName().toString();
                if (fileName.startsWith(".")) continue;
                if (isLangDir(fileName)) {
                    scanLangDir(child, fileName, byId, overlaid);
                    continue;
                }
                if (isTemplateRoot(child)) {
                    String id = shortNameOf(fileName);
                    if (id != null) mergeInto(byId, overlaid, id, child, null);
                    continue;
                }
                if (fileName.contains("jk-templates")
                        || fileName.contains("github.com")
                        || fileName.equals("templates")) {
                    scanNestedG8(child, byId, overlaid, 0);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static boolean isLangDir(String name) {
        return "java".equals(name) || "kotlin".equals(name) || "groovy".equals(name);
    }

    private static void scanLangDir(
            Path langDir, String lang, Map<String, Giter8ShortNames.Entry> byId, Set<String> overlaid) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(langDir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) continue;
                String name = child.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (isTemplateRoot(child)) {
                    String id = shortNameOf(name);
                    if (id != null) mergeInto(byId, overlaid, id, child, lang);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void scanNestedG8(
            Path dir, Map<String, Giter8ShortNames.Entry> byId, Set<String> overlaid, int depth) {
        if (depth > 4 || !Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) continue;
                String name = child.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (name.endsWith(".g8") && isTemplateRoot(child)) {
                    String parent =
                            dir.getFileName() == null ? "" : dir.getFileName().toString();
                    mergeInto(byId, overlaid, shortNameOf(name), child, isLangDir(parent) ? parent : null);
                } else if (depth < 4) {
                    scanNestedG8(child, byId, overlaid, depth + 1);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void mergeInto(
            Map<String, Giter8ShortNames.Entry> byId, Set<String> overlaid, String id, Path templateRoot, String lang) {
        if (id == null || id.isBlank()) return;
        Giter8ShortNames.Entry base = byId.getOrDefault(
                id, new Giter8ShortNames.Entry(id, id, List.of(), Giter8ShortNames.LAYOUT_TRADITIONAL));
        Giter8ShortNames.Entry merged = mergeFromDisk(base, templateRoot);
        if (lang != null && !lang.isBlank()) {
            List<String> langs = new ArrayList<>(merged.languages());
            if (!langs.contains(lang)) {
                langs.add(lang);
                merged = merged.withLanguages(langs);
            }
        }
        byId.put(id, merged);
        overlaid.add(id);
    }

    /** Overlay languages/layout/description from {@code default.properties} (+ layout inference). */
    public static Giter8ShortNames.Entry mergeFromDisk(Giter8ShortNames.Entry base, Path templateRoot) {
        Map<String, String> props = readDefaultProperties(templateRoot);
        List<String> langs = Giter8ShortNames.languagesFromProperties(props);
        if (langs.isEmpty()) langs = base.languages();
        Optional<String> lay = Giter8ShortNames.layoutFromProperties(props);
        String layout = lay.orElseGet(() -> {
            String inferred = inferLayout(templateRoot);
            return inferred != null ? inferred : base.layout();
        });
        String desc = base.description();
        // Prefer catalog description; for unknown short names use props name.
        if ((desc == null || desc.isBlank() || desc.equals(base.id())) && props.containsKey("name")) {
            String n = props.get("name");
            if (n != null && !n.isBlank()) desc = n.strip();
        }
        return new Giter8ShortNames.Entry(base.id(), desc, langs, layout);
    }

    /**
     * Infer layout from the applied content tree under {@code src/main/g8} (or the template root).
     * Returns null when ambiguous.
     */
    public static String inferLayout(Path templateRoot) {
        if (templateRoot == null || !Files.isDirectory(templateRoot)) return null;
        Path g8 = templateRoot.resolve("src/main/g8");
        Path content = Files.isDirectory(g8) ? g8 : templateRoot;
        if (Files.isDirectory(content.resolve("grails-app"))) return Giter8ShortNames.LAYOUT_CUSTOM;
        if (Files.isDirectory(content.resolve("src/main/java"))
                || Files.isDirectory(content.resolve("src/main/kotlin"))
                || Files.isDirectory(content.resolve("src/main/groovy"))) {
            return Giter8ShortNames.LAYOUT_TRADITIONAL;
        }
        if (Files.isDirectory(content.resolve("src")) || Files.isDirectory(content.resolve("test"))) {
            return Giter8ShortNames.LAYOUT_SIMPLE;
        }
        return null;
    }

    public static Map<String, String> readDefaultProperties(Path templateRoot) {
        Map<String, String> out = new LinkedHashMap<>();
        if (templateRoot == null) return out;
        Path g8 = templateRoot.resolve("src/main/g8");
        Path contentRoot = Files.isDirectory(g8) ? g8 : templateRoot;
        for (Path propsFile :
                new Path[] {templateRoot.resolve("default.properties"), contentRoot.resolve("default.properties")}) {
            if (!Files.isRegularFile(propsFile)) continue;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(propsFile)) {
                p.load(in);
            } catch (IOException e) {
                continue;
            }
            for (String name : p.stringPropertyNames()) {
                out.putIfAbsent(name, p.getProperty(name));
            }
        }
        return out;
    }

    static boolean isTemplateRoot(Path p) {
        if (p == null || !Files.isDirectory(p)) return false;
        if (Files.isRegularFile(p.resolve("default.properties"))) return true;
        Path g8 = p.resolve("src/main/g8");
        return Files.isDirectory(g8)
                && (Files.isRegularFile(g8.resolve("default.properties"))
                        || Files.isRegularFile(p.resolve("default.properties")));
    }

    static String shortNameOf(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        String n = fileName;
        if (n.endsWith(".g8")) n = n.substring(0, n.length() - 3);
        n = n.toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z][a-z0-9-]*")) return null;
        return n;
    }

    /**
     * Resolve a short name to a template root directory, searching the same roots the picker uses
     * plus monorepo dogfood near {@code hints} / the process cwd / this class's code source. When
     * nothing is local, best-effort freshen of the official monorepo cache, then search again.
     *
     * @return absolute template root, or empty when the short name cannot be found
     */
    public static Optional<Path> resolveShortName(String shortName, Path... hints) {
        return resolveShortName(shortName, null, hints);
    }

    public static Optional<Path> resolveShortName(String shortName, String lang, Path... hints) {
        if (shortName == null || !shortName.matches("[a-z][a-z0-9-]*")) return Optional.empty();
        Path found = findTemplateDir(searchRoots(hints), shortName, lang);
        if (found != null) return Optional.of(found.toAbsolutePath().normalize());

        // First-party short names often live only in the official monorepo; clone/fetch it once.
        if (Giter8ShortNames.find(shortName).isPresent()) {
            try {
                cc.jumpkick.templates.OfficialTemplatesFreshen.refreshQuiet(s -> {});
            } catch (Throwable ignored) {
                // freshen is best-effort
            }
            found = findTemplateDir(searchRoots(hints), shortName, lang);
            if (found != null) return Optional.of(found.toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    /** All roots used for short-name resolution (deduped, existing dirs preferred first). */
    public static List<Path> searchRoots(Path... hints) {
        Path home = Optional.ofNullable(System.getProperty("user.home"))
                .map(Path::of)
                .orElse(null);
        List<Path> extras = new ArrayList<>();
        // Monorepo dogfood: walk ancestors of hints + user.dir for a templates/ directory.
        if (hints != null) {
            for (Path h : hints) {
                if (h != null) collectDogfood(h, extras);
            }
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) collectDogfood(Path.of(userDir), extras);
        // Development: engine/classes jar lives under …/jk/… — walk up for templates/
        try {
            var loc = Giter8TemplateIndex.class.getProtectionDomain().getCodeSource();
            if (loc != null && loc.getLocation() != null) {
                Path code = Path.of(loc.getLocation().toURI());
                if (Files.isRegularFile(code)) code = code.getParent();
                if (code != null) collectDogfood(code, extras);
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return defaultSearchRoots(home, extras);
    }

    private static void collectDogfood(Path start, List<Path> extras) {
        Path walk = start.toAbsolutePath().normalize();
        if (Files.isRegularFile(walk)) walk = walk.getParent();
        for (int i = 0; i < 10 && walk != null; i++) {
            Path dogfood = walk.resolve("templates");
            if (Files.isDirectory(dogfood) && !extras.contains(dogfood)) {
                extras.add(dogfood);
            }
            walk = walk.getParent();
        }
    }

    static Path findTemplateDir(List<Path> roots, String id) {
        return findTemplateDir(roots, id, null);
    }

    static Path findTemplateDir(List<Path> roots, String id, String lang) {
        if (id == null || id.isBlank() || roots == null) return null;
        String dirName = id + ".g8";
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) continue;
            Path hit = findNamedUnder(root, id, dirName, lang, 0);
            if (hit != null) return hit;
        }
        return null;
    }

    /** DFS for {@code <lang>/id.g8}, {@code id.g8}, or bare {@code id}, depth-capped. */
    private static Path findNamedUnder(Path dir, String id, String dirName, String lang, int depth) {
        if (depth > 5 || dir == null || !Files.isDirectory(dir)) return null;
        Path langHit = langDirTemplate(dir, id, dirName, lang);
        if (langHit != null) return langHit;
        Path a = dir.resolve(dirName);
        if (isTemplateRoot(a)) return a.toAbsolutePath().normalize();
        Path b = dir.resolve(id);
        if (isTemplateRoot(b)) return b.toAbsolutePath().normalize();
        if (depth == 5) return null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) continue;
                String name = child.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (name.equals("templates")
                        || name.endsWith(".g8")
                        || isLangDir(name)
                        || name.contains("jk-templates")
                        || name.contains("github.com")
                        || depth == 0) {
                    Path hit = findNamedUnder(child, id, dirName, lang, depth + 1);
                    if (hit != null) return hit;
                }
            }
        } catch (IOException ignored) {
            // continue
        }
        return null;
    }

    private static Path langDirTemplate(Path dir, String id, String dirName, String lang) {
        List<String> order = new ArrayList<>();
        if (lang != null && !lang.isBlank()) order.add(lang.strip().toLowerCase(Locale.ROOT));
        for (String l : List.of("java", "kotlin", "groovy")) {
            if (!order.contains(l)) order.add(l);
        }
        for (String l : order) {
            Path p = dir.resolve(l).resolve(dirName);
            if (isTemplateRoot(p)) return p.toAbsolutePath().normalize();
            Path q = dir.resolve(l).resolve(id);
            if (isTemplateRoot(q)) return q.toAbsolutePath().normalize();
        }
        return null;
    }
}
