// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code jsonschema2pojo-maven-plugin} is the {@code [json-schema]} preset: {@code
 * <sourceDirectory>} (the plugin's default, {@code src/main/resources/schema}, when the POM leaves
 * it unset) is {@code src}, {@code <targetPackage>} is {@code package}, and the options builds set
 * are their keys, each written only where it differs from the preset's default. The plugin's
 * {@code <outputDirectory>} is the preset's own contribution, so an {@code add-source} root inside
 * it is not written as an {@code extra-src}. {@code <sourcePaths>} with more than one entry is a row.
 */
final class JsonSchemaPlugin {

    static final String ARTIFACT = "jsonschema2pojo-maven-plugin";

    /** What an {@code add-source} root inside the output is, for the build-helper row. */
    static final String ADD_SOURCE_ROW = "the [json-schema] output; no extra-src needed";

    private static final String MAVEN_SRC = "src/main/resources/schema";
    private static final String PRESET_SRC = "src/main/resources/json";
    private static final String DEFAULT_OUTPUT = "target/generated-sources/jsonschema2pojo";

    /** Each boolean option with its key and the preset's default. */
    private static final Map<String, Map.Entry<String, Boolean>> SWITCHES = Map.ofEntries(
            Map.entry("generateBuilders", Map.entry("builders", false)),
            Map.entry("includeConstructors", Map.entry("constructors", false)),
            Map.entry("includeJsr303Annotations", Map.entry("jsr303", false)),
            Map.entry("useJakartaValidation", Map.entry("jakarta-validation", false)),
            Map.entry("serializable", Map.entry("serializable", false)),
            Map.entry("useOptionalForGetters", Map.entry("optional-getters", false)),
            Map.entry("useLongIntegers", Map.entry("long-integers", false)),
            Map.entry("useBigDecimals", Map.entry("big-decimals", false)),
            Map.entry("useTitleAsClassname", Map.entry("title-as-classname", false)),
            Map.entry("includeAdditionalProperties", Map.entry("additional-properties", true)));

    /** Each string option with its key and the values that are the preset's default. */
    private static final Map<String, Map.Entry<String, List<String>>> VALUES = Map.of(
            "targetPackage", Map.entry("package", List.of()),
            "sourceType", Map.entry("source-type", List.of("jsonschema")),
            "annotationStyle", Map.entry("annotation-style", List.of("jackson", "jackson2")),
            "dateTimeType", Map.entry("date-time-type", List.of()),
            "dateType", Map.entry("date-type", List.of()),
            "timeType", Map.entry("time-type", List.of()),
            "outputEncoding", Map.entry("encoding", List.of("utf-8")));

    /** The table (null without the plugin) and the output root the plugin fills. */
    record Mapped(@Nullable PluginConfig table, Map<String, String> outputRoots) {
        static final Mapped NONE = new Mapped(null, Map.of());
    }

    private JsonSchemaPlugin() {}

    static Mapped map(Model model, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, ARTIFACT).orElse(null);
        if (plugin == null) return Mapped.NONE;
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        Map<String, Object> values = new LinkedHashMap<>();
        String src = MAVEN_SRC;
        String output = DEFAULT_OUTPUT;
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            String declared = PluginFacts.child(config, "sourceDirectory");
            if (declared != null) src = SourceTreePlugins.moduleRelative(declared, baseDir);
            Xpp3Dom paths = config.getChild("sourcePaths");
            if (paths != null && paths.getChildCount() > 0) {
                String first = PluginFacts.text(paths.getChild(0));
                if (first != null) src = SourceTreePlugins.moduleRelative(first.strip(), baseDir);
                if (paths.getChildCount() > 1)
                    report.warning(ARTIFACT + ": imported " + src + " only of <sourcePaths>");
            }
            String declaredOutput = PluginFacts.child(config, "outputDirectory");
            if (declaredOutput != null) output = SourceTreePlugins.moduleRelative(declaredOutput, baseDir);
            SWITCHES.forEach((option, key) -> {
                Boolean on =
                        EnvValues.parseBool(PluginFacts.child(config, option)).orElse(null);
                if (on != null && on != key.getValue()) values.put(key.getKey(), on);
            });
            VALUES.forEach((option, key) -> {
                String value = PluginFacts.child(config, option);
                if (value != null && !key.getValue().contains(value.toLowerCase(Locale.ROOT))) {
                    values.put(key.getKey(), value);
                }
            });
        }
        if (!src.equals(PRESET_SRC)) values.put("src", src);
        String version = PluginFacts.usable(plugin.getVersion());
        if (version != null) values.put("version", version);
        report.warning(ARTIFACT + " became [json-schema]; generates from " + src);
        return new Mapped(new PluginConfig("json-schema", values), Map.of(output, ADD_SOURCE_ROW));
    }
}
