// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Giter8 trees bundled in a plugin jar at {@code templates/<lang>/<framework>/<name>.g8/}.
 */
public final class PluginTemplates {

    private PluginTemplates() {}

    /**
     * Per-jar scan memo, keyed by path with the (size, mtime) stamp in the value: mounting every
     * plugin jar as a zip filesystem and parsing each {@code .jk-template.toml} on every picker /
     * resolve call is engine-request-path work that only changes when a jar does.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Path, JarScan> SCAN_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record JarScan(long size, java.nio.file.attribute.FileTime modified, List<TemplateSpec> specs) {}

    /** Every plugin-bundled template as a picker row ({@code root} unset until materialize). */
    public static List<TemplateSpec> list() {
        List<TemplateSpec> out = new ArrayList<>();
        for (PluginDescriptor d : PluginTableRegistry.manifests()) {
            Path jar = PluginTableRegistry.archive(d.id());
            if (jar == null || !Files.isRegularFile(jar)) continue;
            out.addAll(scanJarCached(d.id(), jar));
        }
        out.sort(Comparator.comparing(TemplateSpec::id));
        return List.copyOf(out);
    }

    private static List<TemplateSpec> scanJarCached(String pluginId, Path jar) {
        long size;
        java.nio.file.attribute.FileTime modified;
        try {
            size = Files.size(jar);
            modified = Files.getLastModifiedTime(jar);
        } catch (IOException unstatable) {
            return scanJar(pluginId, jar);
        }
        JarScan hit = SCAN_CACHE.get(jar);
        if (hit != null && hit.size() == size && hit.modified().equals(modified)) {
            return hit.specs();
        }
        List<TemplateSpec> specs = List.copyOf(scanJar(pluginId, jar));
        SCAN_CACHE.put(jar, new JarScan(size, modified, specs));
        return specs;
    }

    static List<TemplateSpec> scanJar(String pluginId, Path jar) {
        List<TemplateSpec> out = new ArrayList<>();
        try (FileSystem fs = zipfs(jar)) {
            Path templates = fs.getPath("templates");
            if (!Files.isDirectory(templates)) return List.of();
            for (String lang : List.of("java", "kotlin", "groovy")) {
                Path langDir = templates.resolve(lang);
                if (!Files.isDirectory(langDir)) continue;
                try (var frameworks = Files.list(langDir)) {
                    for (Path frameworkDir : frameworks.toList()) {
                        if (!Files.isDirectory(frameworkDir)) continue;
                        String framework = frameworkDir.getFileName().toString();
                        if (framework.startsWith(".") || !JkTemplateToml.isKebab(framework)) continue;
                        try (var tmpls = Files.list(frameworkDir)) {
                            for (Path tmpl : tmpls.toList()) {
                                TemplateSpec spec = specFromRoot(pluginId, lang, framework, tmpl);
                                if (spec != null) out.add(spec);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    static @Nullable TemplateSpec specFromRoot(String pluginId, String lang, String framework, Path tmpl) {
        if (!Files.isDirectory(tmpl)) return null;
        String name = JkTemplateToml.nameFromDir(tmpl.getFileName().toString());
        if (name == null) return null;
        Path metaFile = tmpl.resolve(JkTemplateToml.FILE_NAME);
        if (!Files.isRegularFile(metaFile)) return null;
        JkTemplateToml.Meta meta;
        try {
            meta = JkTemplateToml.parse(Files.readString(metaFile, StandardCharsets.UTF_8), metaFile.toString());
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        if (JkTemplateToml.matching(meta, lang, framework, name).isEmpty()) return null;
        return new TemplateSpec(
                TemplateSpec.idOf(lang, framework, name),
                name,
                lang,
                framework,
                meta.description(),
                meta.layouts(),
                TemplateSpec.SOURCE_PLUGIN,
                pluginId,
                null);
    }

    /**
     * Extract {@code templates/<lang>/<framework>/<name>.g8} to a temp dir. Caller owns cleanup.
     */
    public static Path materialize(String pluginId, String lang, String framework, String name) throws IOException {
        Path jar = jarOf(pluginId);
        if (jar == null) {
            throw new IOException("plugin is not an installed jar: " + pluginId);
        }
        String l = lang.strip().toLowerCase(Locale.ROOT);
        String fw = framework.strip().toLowerCase(Locale.ROOT);
        String n = name.strip().toLowerCase(Locale.ROOT);
        String prefix = "templates/" + l + "/" + fw + "/" + n + ".g8";
        try (FileSystem fs = zipfs(jar)) {
            Path src = fs.getPath(prefix);
            if (!Files.isDirectory(src)) {
                throw missing(pluginId, l, fw, n);
            }
            Path tmp = JkDirs.tmp();
            Files.createDirectories(tmp);
            Path dest = Files.createTempDirectory(tmp, "jk-g8-");
            try {
                copyTree(src, dest);
            } catch (IOException e) {
                deleteQuietly(dest);
                throw new IOException("failed to extract " + prefix + " from " + jar + ": " + e.getMessage(), e);
            }
            return dest;
        }
    }

    public static IOException missing(String pluginId, String lang, String framework, String name) {
        return new IOException("plugin template not found: "
                + pluginId
                + " lang="
                + lang
                + " framework="
                + framework
                + " name="
                + name);
    }

    private static @Nullable Path jarOf(String pluginId) {
        PluginDescriptor d = PluginTableRegistry.byIdOrTable(pluginId);
        if (d == null) return null;
        return PluginTableRegistry.archive(d.id());
    }

    private static FileSystem zipfs(Path jar) throws IOException {
        URI uri = URI.create("jar:" + jar.toAbsolutePath().toUri());
        return FileSystems.newFileSystem(uri, Map.of());
    }

    private static void copyTree(Path src, Path dest) throws IOException {
        try (var walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path rel = src.relativize(p);
                Path out = dest;
                String relStr = rel.toString().replace('\\', '/');
                if (relStr.startsWith("/")) relStr = relStr.substring(1);
                if (!relStr.isEmpty() && !relStr.equals(".")) {
                    for (String part : relStr.split("/")) {
                        if (part.isEmpty() || part.equals(".")) continue;
                        out = out.resolve(part);
                    }
                }
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                } else if (Files.isRegularFile(p) && !Files.isSymbolicLink(p)) {
                    if (out.equals(dest)) continue;
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out);
                }
            }
        }
    }

    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path f :
                    walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(f);
            }
        } catch (IOException ignored) {
            // best-effort cleanup of a failed extract
        }
    }
}
