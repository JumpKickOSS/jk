// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Unified template picker and resolver. Scans {@code <lang>/<framework>/<name>.g8} trees that
 * carry {@code .jk-template.toml}. Plugin jar trees overlay disk on id collision.
 */
public final class Giter8TemplateIndex {

    private static final List<String> LANGS = List.of("java", "kotlin", "groovy");

    private Giter8TemplateIndex() {}

    /**
     * Short-TTL memo of the assembled index: MCP {@code templates} and every {@code jk new}
     * resolution re-scanned all disk roots and plugin jars per call — a cold-cache scaffold ran
     * up to three full scans. Five seconds bounds staleness for concurrent edits;
     * {@link #invalidate()} drops it eagerly after a catalog freshen.
     */
    private static volatile PickerMemo PICKER_MEMO;

    private static final long PICKER_TTL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

    private record PickerMemo(List<Path> roots, long atNanos, List<TemplateSpec> specs) {}

    /** Drop the index memo — a freshen just changed what the cache roots hold. */
    public static void invalidate() {
        PICKER_MEMO = null;
    }

    /** Catalog / local roots first ({@code putIfAbsent} precedence), plugin rows overlay by id. */
    public static List<TemplateSpec> picker(List<Path> roots) {
        List<Path> key = roots == null ? List.of() : List.copyOf(roots);
        PickerMemo memo = PICKER_MEMO;
        long now = System.nanoTime();
        if (memo != null && memo.roots().equals(key) && now - memo.atNanos() < PICKER_TTL_NANOS) {
            return memo.specs();
        }
        Map<String, TemplateSpec> byId = new LinkedHashMap<>();
        for (Path root : key) {
            if (root == null) continue;
            scanRoot(root.toAbsolutePath().normalize(), byId, TemplateSpec.SOURCE_CATALOG);
        }
        for (TemplateSpec p : PluginTemplates.list()) {
            byId.put(p.id(), p);
        }
        List<TemplateSpec> specs = List.copyOf(byId.values());
        PICKER_MEMO = new PickerMemo(key, now, specs);
        return specs;
    }

    /**
     * Resolve {@code ref} to a spec in {@code picker(roots)}.
     *
     * <ul>
     *   <li>{@code lang/framework/name} — exact
     *   <li>{@code framework/name} — requested lang first, then java → kotlin → groovy
     *   <li>bare {@code name} — framework {@code none}; requested lang only when set, else walk
     * </ul>
     *
     * @throws IllegalArgumentException when {@code ref} names a framework rather than a template
     */
    public static Optional<TemplateSpec> resolve(String ref, @Nullable String lang, List<Path> roots) {
        if (ref == null || ref.isBlank()) return Optional.empty();
        String r = ref.strip();
        if (r.contains("\\") || r.startsWith(".") || r.startsWith("/")) return Optional.empty();
        List<TemplateSpec> all = picker(roots);
        Map<String, TemplateSpec> byId = new LinkedHashMap<>();
        for (TemplateSpec s : all) byId.put(s.id(), s);

        String[] parts = r.split("/");
        if (parts.length == 3) {
            return Optional.ofNullable(byId.get(TemplateSpec.idOf(parts[0], parts[1], parts[2])));
        }
        if (parts.length == 2) {
            String framework = parts[0].toLowerCase(Locale.ROOT);
            String name = parts[1].toLowerCase(Locale.ROOT);
            if (!JkTemplateToml.isKebab(framework) || !JkTemplateToml.isKebab(name)) return Optional.empty();
            Optional<TemplateSpec> hit = walkLangs(byId, lang, framework, name);
            if (hit.isPresent()) return hit;
            return Optional.empty();
        }
        if (parts.length != 1) return Optional.empty();
        String name = parts[0].toLowerCase(Locale.ROOT);
        if (!JkTemplateToml.isKebab(name)) return Optional.empty();
        Optional<TemplateSpec> none = lang == null || lang.isBlank()
                ? walkLangs(byId, null, TemplateSpec.FRAMEWORK_NONE, name)
                : Optional.ofNullable(byId.get(TemplateSpec.idOf(lang, TemplateSpec.FRAMEWORK_NONE, name)));
        if (none.isPresent()) return none;
        List<String> under = templatesUnderFramework(all, name);
        if (!under.isEmpty()) {
            throw new IllegalArgumentException(name
                    + " is a framework; pick a template: "
                    + String.join(", ", under)
                    + " (e.g. "
                    + name
                    + "/"
                    + under.getFirst()
                    + ")");
        }
        // Bare name living under frameworks: unambiguous resolves, ambiguity names candidates.
        List<TemplateSpec> named = new ArrayList<>();
        for (TemplateSpec s : all) {
            if (!s.name().equals(name)) continue;
            if (lang != null && !lang.isBlank() && !s.language().equals(lang.strip().toLowerCase(Locale.ROOT))) {
                continue;
            }
            named.add(s);
        }
        if (named.size() == 1) return Optional.of(named.getFirst());
        if (named.size() > 1) {
            TreeSet<String> ids = new TreeSet<>();
            for (TemplateSpec s : named) ids.add(s.id());
            throw new IllegalArgumentException(
                    name + " is ambiguous; pick one of: " + String.join(", ", ids));
        }
        return Optional.empty();
    }

    static Optional<TemplateSpec> walkLangs(
            Map<String, TemplateSpec> byId, @Nullable String requested, String framework, String name) {
        List<String> order = new ArrayList<>();
        if (requested != null && !requested.isBlank()) {
            order.add(requested.strip().toLowerCase(Locale.ROOT));
        }
        for (String l : LANGS) {
            if (!order.contains(l)) order.add(l);
        }
        for (String l : order) {
            TemplateSpec hit = byId.get(TemplateSpec.idOf(l, framework, name));
            if (hit != null) return Optional.of(hit);
        }
        return Optional.empty();
    }

    static List<String> templatesUnderFramework(List<TemplateSpec> all, String framework) {
        TreeSet<String> names = new TreeSet<>();
        for (TemplateSpec s : all) {
            if (s.framework().equals(framework)) names.add(s.name());
        }
        return List.copyOf(names);
    }

    public static List<Path> defaultSearchRoots(Path home, List<Path> extras) {
        List<Path> roots = new ArrayList<>();
        String env = System.getenv("JK_TEMPLATES");
        if (env != null && !env.isBlank()) roots.add(Path.of(env));
        if (home != null) {
            roots.add(home.resolve(".jk").resolve("templates"));
            roots.add(home.resolve(".jk").resolve("cache").resolve("templates"));
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

    static void scanRoot(Path root, Map<String, TemplateSpec> byId, String source) {
        if (!Files.isDirectory(root)) return;
        if (scanCatalogLayout(root, byId, source)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) continue;
                String fileName = child.getFileName().toString();
                if (fileName.startsWith(".")) continue;
                // Every child gets scanned (depth-capped): the templates cache holds one clone
                // per configured source, and non-GitHub source URLs produce cache keys a
                // name-allowlist would skip — [templates.sources] catalogs must be visible.
                scanNested(child, byId, source, 0);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void scanNested(Path dir, Map<String, TemplateSpec> byId, String source, int depth) {
        if (depth > 4 || !Files.isDirectory(dir)) return;
        if (scanCatalogLayout(dir, byId, source)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) continue;
                String name = child.getFileName().toString();
                if (name.startsWith(".")) continue;
                scanNested(child, byId, source, depth + 1);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** @return true when {@code dir} itself is a {@code <lang>/<framework>/} catalog root */
    static boolean scanCatalogLayout(Path dir, Map<String, TemplateSpec> byId, String source) {
        boolean any = false;
        for (String lang : LANGS) {
            Path langDir = dir.resolve(lang);
            if (!Files.isDirectory(langDir)) continue;
            try (DirectoryStream<Path> frameworks = Files.newDirectoryStream(langDir)) {
                for (Path frameworkDir : frameworks) {
                    if (!Files.isDirectory(frameworkDir)) continue;
                    String framework = frameworkDir.getFileName().toString();
                    if (framework.startsWith(".") || framework.endsWith(".g8")) continue;
                    if (!JkTemplateToml.isKebab(framework)) continue;
                    any |= scanFrameworkDir(lang, framework, frameworkDir, byId, source);
                }
            } catch (IOException ignored) {
                // best-effort
            }
        }
        return any;
    }

    private static boolean scanFrameworkDir(
            String lang, String framework, Path frameworkDir, Map<String, TemplateSpec> byId, String source) {
        boolean any = false;
        try (DirectoryStream<Path> tmpls = Files.newDirectoryStream(frameworkDir)) {
            for (Path tmpl : tmpls) {
                TemplateSpec spec = specFromDisk(lang, framework, tmpl, source);
                if (spec != null) {
                    byId.putIfAbsent(spec.id(), spec);
                    any = true;
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return any;
    }

    static @Nullable TemplateSpec specFromDisk(String lang, String framework, Path tmpl, String source) {
        if (!Files.isDirectory(tmpl)) return null;
        String name = JkTemplateToml.nameFromDir(tmpl.getFileName().toString());
        if (name == null) return null;
        Optional<JkTemplateToml.Meta> meta = JkTemplateToml.tryRead(tmpl);
        if (meta.isEmpty()) return null;
        if (JkTemplateToml.matching(meta.get(), lang, framework, name).isEmpty()) return null;
        JkTemplateToml.Meta m = meta.get();
        String src = source;
        if (TemplateSpec.SOURCE_CATALOG.equals(source) && isLocalRoot(tmpl)) src = TemplateSpec.SOURCE_LOCAL;
        return new TemplateSpec(
                TemplateSpec.idOf(lang, framework, name),
                name,
                lang,
                framework,
                m.description(),
                m.layouts(),
                src,
                null,
                tmpl.toAbsolutePath().normalize());
    }

    private static boolean isLocalRoot(Path tmpl) {
        String env = System.getenv("JK_TEMPLATES");
        if (env != null && !env.isBlank()) {
            Path envRoot = Path.of(env).toAbsolutePath().normalize();
            if (tmpl.startsWith(envRoot)) return true;
        }
        Path home = Path.of(System.getProperty("user.home", "")).resolve(".jk").resolve("templates");
        return tmpl.startsWith(home.toAbsolutePath().normalize());
    }

    public static boolean isTemplateRoot(Path p) {
        if (p == null || !Files.isDirectory(p)) return false;
        return Files.isRegularFile(p.resolve(JkTemplateToml.FILE_NAME));
    }

    public static List<Path> searchRoots(Path... hints) {
        Path home = Optional.ofNullable(System.getProperty("user.home"))
                .map(Path::of)
                .orElse(null);
        List<Path> extras = new ArrayList<>();
        if (hints != null) {
            for (Path h : hints) {
                if (h != null) collectDogfood(h, extras);
            }
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) collectDogfood(Path.of(userDir), extras);
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
}
