// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The templates jk's own distribution carries: {@code app} and {@code library} in every language
 * that has them, copied from the checkout's {@code templates/} into the engine jar at {@link
 * #RESOURCE_ROOT}. They are the index's last layer, so {@code jk new} works on a fresh home with no
 * catalogue clone and no network, and a catalogue copy of the same id always wins.
 */
public final class ShippedTemplates {

    /** Where the engine jar holds the trees, laid out as {@link TemplateLayout} says. */
    public static final String RESOURCE_ROOT = "cc/jumpkick/giter8/shipped";

    private ShippedTemplates() {}

    /** The jar never changes under a running engine: scanned once. */
    private static final class Holder {
        static final List<TemplateSpec> SPECS = scan(root());
    }

    /** Every shipped template as a picker row ({@code root} unset until {@link #materialize}). */
    public static List<TemplateSpec> list() {
        return Holder.SPECS;
    }

    /** The shipped tree on this classpath, or null when the engine was built without one. */
    static @Nullable Path root() {
        URL url = ShippedTemplates.class.getClassLoader().getResource(RESOURCE_ROOT);
        if (url == null) return null;
        try {
            URI uri = url.toURI();
            if ("jar".equals(uri.getScheme())) {
                try {
                    FileSystems.newFileSystem(uri, Map.of());
                } catch (FileSystemAlreadyExistsException open) {
                    // another reader mounted the engine jar first; Path.of finds it
                }
            }
            return Path.of(uri);
        } catch (URISyntaxException | IOException | RuntimeException e) {
            Log.debug("shipped templates: unreadable at " + url, e);
            return null;
        }
    }

    static List<TemplateSpec> scan(@Nullable Path root) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<TemplateSpec> out = new ArrayList<>();
        try {
            TemplateLayout.scan(root, (lang, framework, tmpl) -> {
                TemplateSpec spec =
                        PluginTemplates.specFromRoot(TemplateSpec.SOURCE_SHIPPED, null, lang, framework, tmpl);
                if (spec != null) out.add(spec);
            });
        } catch (IOException e) {
            Log.debug("shipped templates: scan of " + root + " failed", e);
            return List.of();
        }
        out.sort(Comparator.comparing(TemplateSpec::id));
        return List.copyOf(out);
    }

    /** Extract {@code spec}'s tree to a temp dir. Caller owns cleanup. */
    public static Path materialize(TemplateSpec spec) throws IOException {
        Path root = root();
        Path src = root == null
                ? null
                : root.resolve(TemplateLayout.relativeDir(spec.language(), spec.framework(), spec.name()));
        if (src == null || !Files.isDirectory(src)) {
            throw new IOException("shipped template not found: " + spec.id());
        }
        Path tmp = JkDirs.tmp();
        OwnerOnlyFiles.createDirectories(tmp);
        Path dest = Files.createTempDirectory(tmp, "jk-g8-");
        try {
            // Cross-provider copy (zipfs -> default): PathUtil resolves by string.
            PathUtil.copyTree(src, dest);
        } catch (IOException e) {
            PathUtil.deleteRecursively(dest);
            throw new IOException("failed to extract shipped template " + spec.id() + ": " + e.getMessage(), e);
        }
        return dest;
    }
}
