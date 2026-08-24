// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.library;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.tomlj.TomlTable;

/**
 * Short-name → {@code group:artifact} catalog. Layers (high → low):
 *
 * <ol>
 *   <li>{@code jk-libs.toml} at the workspace root (or standalone project root) — optional
 *   <li>system global ({@link JkDirs#libraryRegistry()}) — managed by {@code jk library update}
 *   <li>bundled classpath resource — offline floor
 * </ol>
 *
 * <p>Per-name shadowing; the bundled layer is always present. There is no host-local catalog file.
 */
public final class LibraryCatalog {

    private static final String BUNDLED_RESOURCE = "/cc/jumpkick/library/libraries.toml";

    private static volatile LibraryCatalog bundled;

    private final List<Layer> layers;

    private LibraryCatalog(List<Layer> layers) {
        this.layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
    }

    /** Downloaded registry mirror: {@link JkDirs#libraryRegistry()}. */
    public static Path downloadedFile() {
        return JkDirs.libraryRegistry();
    }

    /** ETag sidecar next to {@code cacheFile} for conditional-GET revalidation. */
    public static Path etagFileFor(Path cacheFile) {
        return cacheFile.resolveSibling("." + cacheFile.getFileName() + ".etag");
    }

    /** The validating {@code ETag} sidecar for {@link #downloadedFile()}. */
    public static Path etagFile() {
        return etagFileFor(downloadedFile());
    }

    /** Path of the project/workspace catalog file under {@code root}. */
    public static Path projectFile(Path root) {
        return root.resolve(ManifestPaths.LIBRARIES);
    }

    /** Bundled-only catalog (lazy singleton); ignores system/project layers. */
    public static LibraryCatalog bundled() {
        LibraryCatalog local = bundled;
        if (local != null) return local;
        synchronized (LibraryCatalog.class) {
            if (bundled != null) return bundled;
            bundled = new LibraryCatalog(List.of(loadBundledLayer()));
            return bundled;
        }
    }

    /** System catalog only: global → bundled (no project layer). */
    public static LibraryCatalog layered() {
        return layered(w -> {});
    }

    /** As {@link #layered()}, reporting skipped-malformed-layer warnings to {@code warn}. */
    public static LibraryCatalog layered(Consumer<String> warn) {
        List<Layer> chain = new ArrayList<>();
        loadFileLayer(downloadedFile(), "global", warn).ifPresent(chain::add);
        chain.add(loadBundledLayer());
        return new LibraryCatalog(chain);
    }

    /**
     * System catalog plus optional {@code jk-libs.toml} for the project/workspace that owns {@code
     * dir}. {@code dir} is typically a module directory or standalone project root.
     *
     * <p>If {@code dir} is a workspace <em>module</em> and contains its own {@code jk-libs.toml},
     * that is an error — the file is only legal at the workspace root.
     */
    public static LibraryCatalog forProject(Path dir) {
        return forProject(dir, w -> {});
    }

    /** As {@link #forProject(Path)}, reporting skipped-malformed-layer warnings to {@code warn}. */
    public static LibraryCatalog forProject(Path dir, Consumer<String> warn) {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(warn, "warn");
        Path root = catalogRoot(dir);
        Path moduleLibs = dir.toAbsolutePath().normalize().resolve(ManifestPaths.LIBRARIES);
        Path rootLibs = projectFile(root);
        if (!moduleLibs.equals(rootLibs) && Files.isRegularFile(moduleLibs)) {
            throw new IllegalStateException(ManifestPaths.LIBRARIES + " is only allowed at the workspace root ("
                    + rootLibs + "); found " + moduleLibs);
        }
        LibraryCatalog base = layered(warn);
        return loadFileLayer(rootLibs, "project", warn)
                .map(layer -> base.withProjectOverrides(layer.libraries))
                .orElse(base);
    }

    /**
     * Catalog root for {@code dir}: nearest ancestor workspace root (has {@code jk.toml} with
     * {@code [workspace] modules}) when {@code dir} <em>is</em> that root or a declared module;
     * else the nearest ancestor that has {@code jk.toml} (standalone), else {@code dir} itself.
     *
     * <p>A nested {@code jk.toml} that is not a declared workspace module is its own catalog root
     * (JUnit temps under {@code shared/core/build/tmp}, accidental inner projects).
     */
    public static Path catalogRoot(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        Path candidate = normalized;
        Path nearestJkTomlDir = null;
        for (int depth = 0; depth < 8192 && candidate != null; depth++) {
            Path jkToml = candidate.resolve(ManifestPaths.MANIFEST);
            if (Files.isRegularFile(jkToml)) {
                if (nearestJkTomlDir == null) nearestJkTomlDir = candidate;
                if (declaresWorkspaceModules(jkToml)) {
                    if (isWorkspaceOrDeclaredModule(candidate, nearestJkTomlDir, jkToml)) {
                        return candidate;
                    }
                    return nearestJkTomlDir;
                }
            }
            candidate = candidate.getParent();
        }
        return nearestJkTomlDir != null ? nearestJkTomlDir : normalized;
    }

    /** True when {@code projectDir} is the workspace root or an exact {@code [workspace] modules} path. */
    static boolean isWorkspaceOrDeclaredModule(Path workspaceRoot, Path projectDir, Path workspaceJkToml) {
        if (workspaceRoot.equals(projectDir)) return true;
        Path rel = workspaceRoot.relativize(projectDir);
        if (rel.getNameCount() == 0 || rel.startsWith("..")) return false;
        String relPath = rel.toString().replace('\\', '/');
        return workspaceModulePaths(workspaceJkToml).contains(relPath);
    }

    /**
     * Lightweight probe: does {@code jkToml} declare a non-empty {@code [workspace] modules}
     * array? Line-scanned so catalog loading never re-enters full {@code JkBuild} parse. Handles
     * both single-line ({@code modules = ["a"]}) and multi-line array forms.
     */
    static boolean declaresWorkspaceModules(Path jkToml) {
        try {
            boolean inWorkspace = false;
            boolean inModulesArray = false;
            for (String raw : Files.readString(jkToml, StandardCharsets.UTF_8).split("\n", -1)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    int close = line.indexOf(']');
                    String section = close > 1
                            ? line.substring(line.startsWith("[[") ? 2 : 1, close)
                                    .replace("]", "")
                                    .strip()
                            : "";
                    inWorkspace = section.equals("workspace");
                    inModulesArray = false;
                    continue;
                }
                if (!inWorkspace) continue;
                if (line.startsWith("modules")) {
                    int open = line.indexOf('[');
                    if (open < 0) continue;
                    int end = line.lastIndexOf(']');
                    if (end > open) {
                        // modules = [ "a", "b" ] on one line
                        if (!line.substring(open + 1, end).strip().isEmpty()) return true;
                        continue;
                    }
                    // modules = [  … multi-line
                    inModulesArray = true;
                    continue;
                }
                if (inModulesArray) {
                    if (line.contains("\"")) return true; // at least one quoted module path
                    if (line.contains("]")) inModulesArray = false;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** Quoted paths in {@code [workspace] modules} of {@code jkToml}. Empty on unreadable. */
    static Set<String> workspaceModulePaths(Path jkToml) {
        Set<String> out = new LinkedHashSet<>();
        try {
            boolean inWorkspace = false;
            boolean inModulesArray = false;
            for (String raw : Files.readString(jkToml, StandardCharsets.UTF_8).split("\n", -1)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    int close = line.indexOf(']');
                    String section = close > 1
                            ? line.substring(line.startsWith("[[") ? 2 : 1, close)
                                    .replace("]", "")
                                    .strip()
                            : "";
                    inWorkspace = section.equals("workspace");
                    inModulesArray = false;
                    continue;
                }
                if (!inWorkspace) continue;
                if (line.startsWith("modules")) {
                    int open = line.indexOf('[');
                    if (open < 0) continue;
                    int end = line.lastIndexOf(']');
                    if (end > open) {
                        collectQuoted(line.substring(open + 1, end), out);
                        continue;
                    }
                    inModulesArray = true;
                    collectQuoted(line.substring(open + 1), out);
                    continue;
                }
                if (inModulesArray) {
                    int close = line.indexOf(']');
                    if (close >= 0) {
                        collectQuoted(line.substring(0, close), out);
                        inModulesArray = false;
                    } else {
                        collectQuoted(line, out);
                    }
                }
            }
        } catch (IOException e) {
            return Set.of();
        }
        return out;
    }

    private static void collectQuoted(String s, Set<String> out) {
        int i = 0;
        while (i < s.length()) {
            int a = s.indexOf('"', i);
            if (a < 0) return;
            int b = s.indexOf('"', a + 1);
            if (b < 0) return;
            String v = s.substring(a + 1, b).strip();
            if (!v.isEmpty()) out.add(v);
            i = b + 1;
        }
    }

    /** Test seam: build a catalog from a single in-memory map. */
    public static LibraryCatalog of(Map<String, Module> libraries) {
        return new LibraryCatalog(List.of(new Layer("test", Map.copyOf(libraries))));
    }

    /** Test seam: parse a single layer from a TOML string. */
    public static LibraryCatalog parse(String toml) {
        return new LibraryCatalog(List.of(new Layer("inline", parseTable(toml, "inline"))));
    }

    /** View with project {@code jk-libs.toml} entries as the top layer. */
    public LibraryCatalog withProjectOverrides(Map<String, Module> projectLibraries) {
        if (projectLibraries == null || projectLibraries.isEmpty()) return this;
        List<Layer> chain = new ArrayList<>(layers.size() + 1);
        chain.add(new Layer("project", Map.copyOf(projectLibraries)));
        chain.addAll(layers);
        return new LibraryCatalog(chain);
    }

    /**
     * Look up a short name. Walks layers in order; the first hit wins. Returns empty when no layer
     * carries the name.
     */
    public Optional<Module> lookup(String name) {
        if (name == null) return Optional.empty();
        for (Layer layer : layers) {
            Module hit = layer.libraries.get(name);
            if (hit != null) return Optional.of(hit);
        }
        return Optional.empty();
    }

    /**
     * Reverse lookup: first short name whose module key equals {@code moduleKey} (layers in lookup
     * order). Empty when blank or unmapped.
     */
    public Optional<String> nameForModule(String moduleKey) {
        if (moduleKey == null || moduleKey.isBlank()) return Optional.empty();
        for (Layer layer : layers) {
            for (var e : layer.libraries.entrySet()) {
                if (moduleKey.equals(e.getValue().moduleKey())) return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    /** All names across every layer, sorted lexicographically. */
    public Set<String> names() {
        Set<String> all = new TreeSet<>();
        for (Layer layer : layers) all.addAll(layer.libraries.keySet());
        return all;
    }

    /**
     * For diagnostics: report which layer a name resolves through. Empty when no layer carries it.
     */
    public Optional<Source> source(String name) {
        if (name == null) return Optional.empty();
        for (Layer layer : layers) {
            Module hit = layer.libraries.get(name);
            if (hit != null) return Optional.of(new Source(layer.name, hit));
        }
        return Optional.empty();
    }

    /** All layers, in lookup order (highest precedence first). */
    public List<String> layerNames() {
        List<String> out = new ArrayList<>(layers.size());
        for (Layer layer : layers) out.add(layer.name);
        return List.copyOf(out);
    }

    public int size() {
        return names().size();
    }

    /**
     * Best-effort name suggestions for an unknown library. Splits the candidate on {@code -} and
     * returns up to {@code maxResults} catalog names that contain every non-empty part as a substring
     * (case-insensitive). Useful for "did you mean" diagnostics — typing {@code jackson-databind}
     * surfaces {@code jackson2-databind} and {@code jackson3-databind} since both contain "jackson"
     * and "databind".
     *
     * <p>Walks the layered chain in lookup-priority order, so a project-level override shadows a
     * same-named bundled entry in the suggestion list too.
     */
    public List<String> suggestionsFor(String unknownName, int maxResults) {
        if (unknownName == null || unknownName.isBlank() || maxResults <= 0) {
            return List.of();
        }
        String lowerInput = unknownName.toLowerCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        for (String p : lowerInput.split("-")) {
            if (!p.isEmpty()) parts.add(p);
        }
        if (parts.isEmpty()) return List.of();
        List<String> hits = new ArrayList<>();
        for (String name : names()) {
            if (name.equalsIgnoreCase(unknownName)) continue; // not a "suggestion"
            String lower = name.toLowerCase(Locale.ROOT);
            boolean allMatch = true;
            for (String p : parts) {
                if (!lower.contains(p)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                hits.add(name);
                if (hits.size() >= maxResults) break;
            }
        }
        return List.copyOf(hits);
    }

    /** The non-version half of a Maven coordinate. */
    public record Module(String group, String artifact) {
        public Module {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            if (group.isBlank()) throw new IllegalArgumentException("group must not be blank");
            if (artifact.isBlank()) throw new IllegalArgumentException("artifact must not be blank");
        }

        public String moduleKey() {
            return group + ":" + artifact;
        }
    }

    /** Where a lookup resolved — used by {@code jk library list}. */
    public record Source(String layer, Module module) {}

    private record Layer(String name, Map<String, Module> libraries) {}

    private static Layer loadBundledLayer() {
        try (InputStream in = LibraryCatalog.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new IOException("missing classpath resource: " + BUNDLED_RESOURCE);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Layer("bundled", parseTable(text, BUNDLED_RESOURCE));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load bundled library catalog from " + BUNDLED_RESOURCE, e);
        }
    }

    private static Optional<Layer> loadFileLayer(Path file, String layerName, Consumer<String> warn) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(new Layer(layerName, parseTable(text, file.toString())));
        } catch (IOException | IllegalStateException e) {
            // Fail soft: a malformed downloaded/project layer should warn, not break every jk
            // invocation. Hand the message to the caller's sink and skip the layer.
            warn.accept("warning: ignoring library catalog layer at " + file + " — " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parse a {@code [libraries]} table from catalog TOML source. A line scanner, not tomlj: the
     * catalog files (bundled resource, system global, {@code jk-libs.toml}) are a jk-owned flat
     * format — {@code name = "group:artifact"} — and this parse runs client-side (list/search/
     * suggestions, tool targets, scaffold), where the thin client ships no TOML parser. Validation
     * is per-entry and strict: a malformed entry throws with a path-qualified message.
     */
    static Map<String, Module> parseTable(String toml, String displayPath) {
        Map<String, Module> out = new LinkedHashMap<>();
        boolean seenTable = false;
        boolean inTable = false;
        for (String raw : toml.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                int close = line.indexOf(']');
                String section = close > 1
                        ? line.substring(line.startsWith("[[") ? 2 : 1, close)
                                .replace("]", "")
                                .strip()
                        : "";
                inTable = section.equals("libraries");
                seenTable |= inTable;
                continue;
            }
            if (!inTable) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalStateException(displayPath + " has invalid TOML: unexpected line `" + line + "`");
            }
            String name = unquoteKey(line.substring(0, eq).strip());
            String rest = line.substring(eq + 1).strip();
            if (rest.length() < 2 || rest.charAt(0) != '"') {
                throw new IllegalStateException(
                        displayPath + ".libraries." + name + " must be a string of the form \"group:artifact\"");
            }
            int end = rest.indexOf('"', 1);
            if (end < 0) {
                throw new IllegalStateException(displayPath + " has invalid TOML: unterminated string for " + name);
            }
            String coord = rest.substring(1, end);
            int sep = coord.indexOf(':');
            if (sep <= 0 || sep == coord.length() - 1) {
                throw new IllegalStateException(
                        displayPath + ".libraries." + name + " must be \"group:artifact\" — got: " + coord);
            }
            if (coord.indexOf(':', sep + 1) >= 0) {
                throw new IllegalStateException(displayPath
                        + ".libraries."
                        + name
                        + " carries a version — strip it; the catalog is name→coord only: "
                        + coord);
            }
            rejectReservedSeparator(displayPath, name, coord);
            out.put(name, new Module(coord.substring(0, sep), coord.substring(sep + 1)));
        }
        if (!seenTable) {
            throw new IllegalStateException(displayPath + " is missing the required [libraries] table");
        }
        return out;
    }

    /**
     * {@code |} is the {@code CatalogReadAck} packed-field separator; a name or coordinate
     * carrying it would silently shift every subsequent column on decode (JK-2169), so it is
     * rejected at parse time in both parsers.
     */
    private static void rejectReservedSeparator(String displayPath, String name, String coord) {
        if (name.indexOf('|') >= 0 || coord.indexOf('|') >= 0) {
            throw new IllegalStateException(
                    displayPath + ".libraries." + name + " must not contain `|` (reserved separator)");
        }
    }

    /** Strip optional quotes from a TOML key ({@code "a.b" = …}). */
    private static String unquoteKey(String key) {
        if (key.length() >= 2 && (key.charAt(0) == '"' || key.charAt(0) == '\'')) {
            char q = key.charAt(0);
            if (key.charAt(key.length() - 1) == q) return key.substring(1, key.length() - 1);
        }
        return key;
    }

    /**
     * Parse an already-located {@code [libraries]} sub-table. Used when a caller already navigated
     * to the table (tests / importers).
     */
    public static Map<String, Module> parseLibrariesTable(TomlTable table, String displayPath) {
        Map<String, Module> out = new LinkedHashMap<>();
        for (String name : table.keySet()) {
            Object raw = table.get(name);
            if (!(raw instanceof String coord)) {
                throw new IllegalStateException(
                        displayPath + ".libraries." + name + " must be a string of the form \"group:artifact\"");
            }
            int sep = coord.indexOf(':');
            if (sep <= 0 || sep == coord.length() - 1) {
                throw new IllegalStateException(
                        displayPath + ".libraries." + name + " must be \"group:artifact\" — got: " + coord);
            }
            if (coord.indexOf(':', sep + 1) >= 0) {
                throw new IllegalStateException(displayPath
                        + ".libraries."
                        + name
                        + " carries a version — strip it; the catalog is name→coord only: "
                        + coord);
            }
            rejectReservedSeparator(displayPath, name, coord);
            out.put(name, new Module(coord.substring(0, sep), coord.substring(sep + 1)));
        }
        return out;
    }
}
