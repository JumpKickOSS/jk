// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Giter8 trees bundled in a plugin jar at {@code templates/<lang>/<kind>/}.
 */
public final class PluginTemplates {

    private PluginTemplates() {}

    public static boolean isPluginTemplate(String name) {
        PluginDescriptor d = PluginTableRegistry.byIdOrTable(name);
        if (d == null) return false;
        Path jar = PluginTableRegistry.archive(d.id());
        if (jar == null || !Files.isRegularFile(jar)) return false;
        return !langs(d.id()).isEmpty();
    }

    public static List<String> langs(String pluginId) {
        List<String> out = new ArrayList<>();
        for (String lang : List.of("java", "kotlin", "groovy")) {
            if (!kinds(pluginId, lang).isEmpty()) out.add(lang);
        }
        return List.copyOf(out);
    }

    public static List<String> kinds(String pluginId, String lang) {
        Path jar = jarOf(pluginId);
        if (jar == null) return List.of();
        String prefix = "templates/" + lang + "/";
        List<String> out = new ArrayList<>();
        try (FileSystem fs = zipfs(jar)) {
            Path dir = fs.getPath(prefix);
            if (!Files.isDirectory(dir)) return List.of();
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> !n.startsWith("."))
                        .sorted()
                        .forEach(out::add);
            }
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * Extract {@code templates/<lang>/<kind>} to a temp dir (ZipFileSystem paths are awkward to
     * walk after the fs is closed). Caller owns cleanup of the parent tmp tree.
     */
    public static Path materialize(String pluginId, String lang, String kind) throws IOException {
        Path jar = jarOf(pluginId);
        if (jar == null) {
            throw new IOException("plugin is not an installed jar: " + pluginId);
        }
        String l = lang.strip().toLowerCase(Locale.ROOT);
        String k = kind.strip();
        String prefix = "templates/" + l + "/" + k + "/";
        try (FileSystem fs = zipfs(jar)) {
            Path src = fs.getPath(prefix);
            if (!Files.isDirectory(src)) {
                throw missing(pluginId, l, k);
            }
            Path dest = Files.createTempDirectory(JkDirs.tmp(), "jk-g8-");
            copyTree(src, dest);
            return dest;
        }
    }

    public static Optional<String> resolveLang(String pluginId, @Nullable String requested) {
        List<String> available = langs(pluginId);
        if (requested != null && !requested.isBlank()) {
            String l = requested.strip().toLowerCase(Locale.ROOT);
            if (available.contains(l)) return Optional.of(l);
            return Optional.empty();
        }
        return Giter8ShortNames.defaultLang(available);
    }

    public static IOException missing(String pluginId, String lang, String kind) {
        List<String> langs = langs(pluginId);
        List<String> kinds = kinds(pluginId, lang);
        return new IOException("plugin template not found: " + pluginId + " lang=" + lang + " kind=" + kind + " (langs="
                + langs + " kinds=" + kinds + ")");
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
                Path out = dest.resolve(rel.toString().replace('\\', '/'));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                } else if (Files.isRegularFile(p) && !Files.isSymbolicLink(p)) {
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out);
                }
            }
        }
    }
}
