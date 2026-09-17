// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Resource;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code localizer-maven-plugin} is the {@code [localizer]} preset: {@code <fileMask>} is
 * {@code mask}, the POM's resource directories are {@code resources}, {@code <outputEncoding>} is
 * {@code encoding}, {@code <accessModifierAnnotations>}, {@code <strictTypes>} and
 * {@code <keyPattern>} are their keys, the plugin version is {@code version} — each written only
 * when it differs from the preset's default. The plugin's {@code <outputDirectory>} is the preset's
 * own contribution, so an {@code add-source} root inside it is not written as an {@code extra-src}.
 */
final class LocalizerPlugin {

    static final String ARTIFACT = "localizer-maven-plugin";

    /** What an {@code add-source} root inside the output is, for the build-helper row. */
    static final String ADD_SOURCE_ROW = "the localizer preset's output; `[localizer]` folds the generated"
            + " `Messages` classes into the compile itself, so no `extra-src` root is written";

    private static final String DEFAULT_OUTPUT = "target/generated-sources/localizer";
    private static final String DEFAULT_MASK = "Messages.properties";
    private static final String DEFAULT_ENCODING = "UTF-8";
    private static final String MAIN_RESOURCES = "src/main/resources";

    /** The table (null without the plugin) and the output root the plugin fills. */
    record Mapped(@Nullable PluginConfig table, Map<String, String> outputRoots) {
        static final Mapped NONE = new Mapped(null, Map.of());
    }

    private LocalizerPlugin() {}

    static Mapped map(Model model, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, ARTIFACT).orElse(null);
        if (plugin == null) return Mapped.NONE;
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        Map<String, Object> values = new LinkedHashMap<>();
        String output = DEFAULT_OUTPUT;
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            String mask = PluginFacts.child(config, "fileMask");
            if (mask != null && !mask.equals(DEFAULT_MASK)) values.put("mask", mask);
            String encoding = PluginFacts.child(config, "outputEncoding");
            if (encoding != null && !encoding.equalsIgnoreCase(DEFAULT_ENCODING)) values.put("encoding", encoding);
            String keyPattern = PluginFacts.child(config, "keyPattern");
            if (keyPattern != null) values.put("key-pattern", keyPattern);
            if (EnvValues.parseBool(PluginFacts.child(config, "strictTypes")).orElse(false)) {
                values.put("strict-types", true);
            }
            if (EnvValues.parseBool(PluginFacts.child(config, "accessModifierAnnotations"))
                    .orElse(false)) {
                values.put("access-modifier-annotations", true);
            }
            String declaredOutput = PluginFacts.child(config, "outputDirectory");
            if (declaredOutput != null) output = SourceTreePlugins.moduleRelative(declaredOutput, baseDir);
        }
        List<String> resourceDirs = resourceDirs(model, baseDir);
        if (!resourceDirs.equals(List.of(MAIN_RESOURCES))) values.put("resources", resourceDirs);
        String version = PluginFacts.usable(plugin.getVersion());
        if (version != null) values.put("version", version);
        report.warning("`" + ARTIFACT + "` is `[localizer]`: the `Messages` classes are generated into the compile"
                + " from the bundles under " + String.join(", ", resourceDirs) + "; the module's"
                + " `org.jvnet.localizer:localizer` dependency stays, the generated classes read it at run time.");
        return new Mapped(new PluginConfig("localizer", values), Map.of(output, ADD_SOURCE_ROW));
    }

    /** The module-relative main resource directories the plugin scans, the layout's when the POM lists none. */
    static List<String> resourceDirs(Model model, @Nullable Path baseDir) {
        List<String> dirs = new ArrayList<>();
        if (model.getBuild() != null) {
            for (Resource resource : model.getBuild().getResources()) {
                String raw = resource.getDirectory();
                if (raw == null || raw.isBlank()) continue;
                String dir = SourceTreePlugins.moduleRelative(raw.trim(), baseDir);
                if (!dirs.contains(dir)) dirs.add(dir);
            }
        }
        if (dirs.isEmpty()) dirs.add(MAIN_RESOURCES);
        return dirs;
    }
}
