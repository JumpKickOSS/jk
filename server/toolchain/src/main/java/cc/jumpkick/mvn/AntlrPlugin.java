// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code antlr4-maven-plugin} is the {@code [antlr]} preset: {@code <sourceDirectory>} is
 * {@code src}, {@code <libDirectory>} is {@code lib}, {@code <listener>} / {@code <visitor>} /
 * {@code <inputEncoding>} / {@code <arguments>} / {@code <options>} are their keys,
 * {@code <treatWarningsAsErrors>} is the tool's {@code -Werror} argument, the plugin version is
 * {@code version} — each written only when it differs from the preset's default. The plugin's
 * {@code <outputDirectory>} is the preset's own contribution, so an {@code add-source} root inside
 * it is not written as an {@code extra-src}. {@code <includes>} / {@code <excludes>} and
 * {@code <generateTestSources>} have no key and are a row.
 */
final class AntlrPlugin {

    static final String ARTIFACT = "antlr4-maven-plugin";

    /** What an {@code add-source} root inside the output is, for the build-helper row. */
    static final String ADD_SOURCE_ROW = "the antlr preset's output; `[antlr]` folds the generated parsers into the"
            + " compile itself, so no `extra-src` root is written";

    private static final String DEFAULT_OUTPUT = "target/generated-sources/antlr4";
    private static final String DEFAULT_SRC = "src/main/antlr4";
    private static final String DEFAULT_VERSION = "4.13.2";

    /** The table (null without the plugin) and the output root the plugin fills. */
    record Mapped(@Nullable PluginConfig table, Map<String, String> outputRoots) {
        static final Mapped NONE = new Mapped(null, Map.of());
    }

    private AntlrPlugin() {}

    static Mapped map(Model model, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, ARTIFACT).orElse(null);
        if (plugin == null) return Mapped.NONE;
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        Map<String, Object> values = new LinkedHashMap<>();
        String src = DEFAULT_SRC;
        String lib = null;
        String output = DEFAULT_OUTPUT;
        List<String> arguments = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> uncovered = new LinkedHashSet<>();
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            String declaredSrc = PluginFacts.child(config, "sourceDirectory");
            if (declaredSrc != null) src = SourceTreePlugins.moduleRelative(declaredSrc, baseDir);
            String declaredLib = PluginFacts.child(config, "libDirectory");
            if (declaredLib != null) lib = SourceTreePlugins.moduleRelative(declaredLib, baseDir);
            String declaredOutput = PluginFacts.child(config, "outputDirectory");
            if (declaredOutput != null) output = SourceTreePlugins.moduleRelative(declaredOutput, baseDir);
            String listener = PluginFacts.child(config, "listener");
            if (listener != null && !EnvValues.parseBool(listener).orElse(true)) values.put("listener", false);
            if (EnvValues.parseBool(PluginFacts.child(config, "visitor")).orElse(false)) values.put("visitor", true);
            String encoding = PluginFacts.child(config, "inputEncoding");
            if (encoding != null) values.put("encoding", encoding);
            if (EnvValues.parseBool(PluginFacts.child(config, "treatWarningsAsErrors"))
                    .orElse(false)) {
                arguments.add("-Werror");
            }
            arguments.addAll(children(config, "arguments"));
            Xpp3Dom declaredOptions = config.getChild("options");
            if (declaredOptions != null) {
                for (Xpp3Dom option : declaredOptions.getChildren()) {
                    String value = PluginFacts.usable(option.getValue());
                    if (value != null) options.put(option.getName(), value);
                }
            }
            for (String name : List.of("includes", "excludes", "generateTestSources")) {
                if (config.getChild(name) != null) uncovered.add("`<" + name + ">`");
            }
        }
        if (!src.equals(DEFAULT_SRC)) values.put("src", src);
        if (lib != null && !lib.equals(src + "/imports")) values.put("lib", lib);
        if (!arguments.isEmpty()) values.put("arguments", arguments);
        if (!options.isEmpty()) values.put("options", options);
        String version = PluginFacts.usable(plugin.getVersion());
        if (version != null && !version.equals(DEFAULT_VERSION)) values.put("version", version);
        if (!uncovered.isEmpty()) {
            report.warning("`" + ARTIFACT + "` " + String.join(", ", uncovered) + " have no `[antlr]` key; the preset"
                    + " generates from every `.g4` under `" + src + "` into the main compile.");
        }
        report.warning("`" + ARTIFACT + "` is `[antlr]`: the parsers are generated into the compile from the grammars"
                + " under `" + src + "`, a package per grammar directory; the module's `org.antlr:antlr4-runtime`"
                + " dependency stays and is expected at the tool's version ("
                + (version != null ? version : DEFAULT_VERSION) + ").");
        return new Mapped(new PluginConfig("antlr", values), Map.of(output, ADD_SOURCE_ROW));
    }

    /** The usable text of each child of {@code <list>}, in order. */
    private static List<String> children(Xpp3Dom config, String list) {
        List<String> out = new ArrayList<>();
        Xpp3Dom holder = config.getChild(list);
        if (holder == null) return out;
        for (Xpp3Dom child : holder.getChildren()) {
            String value = PluginFacts.usable(child.getValue());
            if (value != null) out.add(value);
        }
        return out;
    }
}
