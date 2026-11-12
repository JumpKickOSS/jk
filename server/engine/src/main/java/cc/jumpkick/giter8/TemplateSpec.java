// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One Giter8 template: identity {@code language/name}, or {@code language/framework/name} when it
 * belongs to a framework. Catalog, local, and plugin trees share this shape so HTTP, MCP, and CLI
 * resolve through one index, and every surface shows the same id.
 */
public record TemplateSpec(
        String id,
        String name,
        String language,
        String framework,
        String description,
        List<String> layouts,
        String source,
        @Nullable String pluginId,
        @Nullable Path root) {

    /** The framework of a template that has none; never part of its id or its path. */
    public static final String FRAMEWORK_NONE = "none";

    public static final String SOURCE_PLUGIN = "plugin";
    public static final String SOURCE_CATALOG = "catalog";
    public static final String SOURCE_LOCAL = "local";
    /** In jk's own distribution ({@link ShippedTemplates}): served when no catalogue copy is. */
    public static final String SOURCE_SHIPPED = "shipped";

    public TemplateSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(framework, "framework");
        if (description == null) description = "";
        layouts = layouts == null || layouts.isEmpty()
                ? List.of(Giter8ShortNames.LAYOUT_TRADITIONAL, Giter8ShortNames.LAYOUT_SIMPLE)
                : List.copyOf(layouts);
        source = source == null || source.isBlank() ? SOURCE_LOCAL : source;
        id = idOf(language, framework, name);
    }

    /** {@code language/name}, or {@code language/framework/name} when there is a framework. */
    public static String idOf(String language, String framework, String name) {
        String l = language.strip().toLowerCase(Locale.ROOT);
        String f = framework.strip().toLowerCase(Locale.ROOT);
        String n = name.strip().toLowerCase(Locale.ROOT);
        return f.isEmpty() || FRAMEWORK_NONE.equals(f) ? l + "/" + n : l + "/" + f + "/" + n;
    }

    public boolean supportsLayout(String layout) {
        if (layout == null || layout.isBlank()) return true;
        String n = Giter8ShortNames.normalizeLayout(layout);
        for (String l : layouts) {
            if (l.equals(n)) return true;
        }
        return false;
    }

    public TemplateSpec withRoot(Path newRoot) {
        return new TemplateSpec(id, name, language, framework, description, layouts, source, pluginId, newRoot);
    }
}
