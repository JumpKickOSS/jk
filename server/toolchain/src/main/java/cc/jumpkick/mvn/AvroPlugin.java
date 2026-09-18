// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code avro-maven-plugin} is the {@code [avro]} preset: {@code <sourceDirectory>} is {@code src},
 * {@code <stringType>} is {@code string-type} (the Maven plugin's own default, {@code CharSequence},
 * is written when the POM leaves it unset, since the preset's is {@code String}),
 * {@code <fieldVisibility>}, {@code <createSetters>}, {@code <createOptionalGetters>} and
 * {@code <enableDecimalLogicalType>} are their keys, the plugin version is {@code version} — each
 * written only when it differs from the preset's default. The plugin's {@code <outputDirectory>}
 * is the preset's own contribution, so an {@code add-source} root inside it is not written as an
 * {@code extra-src}. {@code <imports>} has no key — the preset orders the schemas by definition
 * itself — and {@code <includes>} / {@code <excludes>} / {@code <testSourceDirectory>} are a row.
 */
final class AvroPlugin {

    static final String ARTIFACT = "avro-maven-plugin";

    /** What an {@code add-source} root inside the output is, for the build-helper row. */
    static final String ADD_SOURCE_ROW = "the avro preset's output; `[avro]` folds the generated classes into the"
            + " compile itself, so no `extra-src` root is written";

    private static final String DEFAULT_OUTPUT = "target/generated-sources/avro";
    private static final String DEFAULT_SRC = "src/main/avro";
    private static final String DEFAULT_VERSION = "1.12.2";
    /** The Maven plugin's default string type — not the preset's. */
    private static final String MAVEN_STRING_TYPE = "CharSequence";

    /** The table (null without the plugin) and the output root the plugin fills. */
    record Mapped(@Nullable PluginConfig table, Map<String, String> outputRoots) {
        static final Mapped NONE = new Mapped(null, Map.of());
    }

    private AvroPlugin() {}

    static Mapped map(Model model, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, ARTIFACT).orElse(null);
        if (plugin == null) return Mapped.NONE;
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        Map<String, Object> values = new LinkedHashMap<>();
        String src = DEFAULT_SRC;
        String output = DEFAULT_OUTPUT;
        String stringType = MAVEN_STRING_TYPE;
        Set<String> uncovered = new LinkedHashSet<>();
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            String declaredSrc = PluginFacts.child(config, "sourceDirectory");
            if (declaredSrc != null) src = SourceTreePlugins.moduleRelative(declaredSrc, baseDir);
            String declaredOutput = PluginFacts.child(config, "outputDirectory");
            if (declaredOutput != null) output = SourceTreePlugins.moduleRelative(declaredOutput, baseDir);
            String declaredStringType = PluginFacts.child(config, "stringType");
            if (declaredStringType != null) stringType = declaredStringType;
            String visibility = PluginFacts.child(config, "fieldVisibility");
            if (visibility != null && !visibility.equalsIgnoreCase("PRIVATE")) {
                values.put("field-visibility", visibility.toUpperCase(Locale.ROOT));
            }
            String setters = PluginFacts.child(config, "createSetters");
            if (setters != null && !EnvValues.parseBool(setters).orElse(true)) values.put("setters", false);
            if (EnvValues.parseBool(PluginFacts.child(config, "createOptionalGetters"))
                    .orElse(false)) {
                values.put("optional-getters", true);
            }
            if (EnvValues.parseBool(PluginFacts.child(config, "enableDecimalLogicalType"))
                    .orElse(false)) {
                values.put("decimal-logical-type", true);
            }
            for (String name : List.of("includes", "excludes", "testSourceDirectory", "testOutputDirectory")) {
                if (config.getChild(name) != null) uncovered.add("`<" + name + ">`");
            }
        }
        if (!src.equals(DEFAULT_SRC)) values.put("src", src);
        if (!stringType.equals("String")) values.put("string-type", stringType);
        String version = PluginFacts.usable(plugin.getVersion());
        if (version != null && !version.equals(DEFAULT_VERSION)) values.put("version", version);
        if (!uncovered.isEmpty()) {
            report.warning("`" + ARTIFACT + "` " + String.join(", ", uncovered) + " have no `[avro]` key; the preset"
                    + " generates from every schema, protocol and IDL file under `" + src + "` into the main compile.");
        }
        report.warning("`" + ARTIFACT + "` is `[avro]`: the classes are generated into the compile from the files"
                + " under `" + src + "`, a schema naming a type another file defines in any order; the module's"
                + " `org.apache.avro:avro` dependency stays and is expected at the compiler's version ("
                + (version != null ? version : DEFAULT_VERSION) + ").");
        return new Mapped(new PluginConfig("avro", values), Map.of(output, ADD_SOURCE_ROW));
    }
}
