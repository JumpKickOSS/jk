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
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * The two JAXB schema-compiler plugins are the {@code [jaxb]} preset. MojoHaus's {@code
 * jaxb2-maven-plugin}: the first {@code <sources>} directory is {@code src}, {@code <packageName>}
 * is {@code package}, {@code <xjbSources>} are {@code bindings}, {@code <encoding>},
 * {@code <extension>} and {@code <arguments>} are their keys, {@code <noPackageLevelAnnotations>}
 * is the {@code -npa} argument, and a {@code schemagen} goal is a row. The jvnet {@code
 * maven-jaxb2-plugin} / {@code jaxb-maven-plugin}: {@code <schemaDirectory>} is {@code src},
 * {@code <generatePackage>} is {@code package}, {@code <bindingDirectory>} is a binding, {@code
 * <args>} are {@code arguments}. Either plugin's output directory is the preset's own
 * contribution, so an {@code add-source} root inside it is not written as an {@code extra-src}.
 */
final class JaxbPlugin {

    static final String MOJOHAUS = "jaxb2-maven-plugin";
    static final String JVNET = "maven-jaxb2-plugin";
    static final String JVNET_RENAMED = "jaxb-maven-plugin";

    /** What an {@code add-source} root inside the output is, for the build-helper row. */
    static final String ADD_SOURCE_ROW = "the jaxb preset's output; `[jaxb]` folds the generated classes into the"
            + " compile itself, so no `extra-src` root is written";

    private static final String DEFAULT_SRC = "src/main/xsd";

    /** The table (null without a plugin) and the output root the plugin fills. */
    record Mapped(@Nullable PluginConfig table, Map<String, String> outputRoots) {
        static final Mapped NONE = new Mapped(null, Map.of());
    }

    private JaxbPlugin() {}

    static Mapped map(Model model, ImportReport.Builder report) {
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        Plugin mojohaus = PluginFacts.plugin(model, MOJOHAUS).orElse(null);
        if (mojohaus != null) return mojohaus(mojohaus, baseDir, report);
        for (String artifact : List.of(JVNET, JVNET_RENAMED)) {
            Plugin jvnet = PluginFacts.plugin(model, artifact).orElse(null);
            if (jvnet != null) return jvnet(artifact, jvnet, baseDir, report);
        }
        return Mapped.NONE;
    }

    private static Mapped mojohaus(Plugin plugin, @Nullable Path baseDir, ImportReport.Builder report) {
        Map<String, Object> values = new LinkedHashMap<>();
        String src = DEFAULT_SRC;
        String output = "target/generated-sources/jaxb";
        List<String> bindings = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            List<String> sources = children(config, "sources");
            if (!sources.isEmpty()) {
                src = SourceTreePlugins.moduleRelative(sources.getFirst(), baseDir);
                if (sources.size() > 1) {
                    report.warning("`" + MOJOHAUS + "` names " + sources.size() + " `<sources>`; `[jaxb]` compiles"
                            + " one directory, `" + src + "` — move the others under it.");
                }
            }
            String declaredOutput = PluginFacts.child(config, "outputDirectory");
            if (declaredOutput != null) output = SourceTreePlugins.moduleRelative(declaredOutput, baseDir);
            String pkg = PluginFacts.child(config, "packageName");
            if (pkg != null) values.put("package", pkg);
            for (String xjb : children(config, "xjbSources"))
                bindings.add(SourceTreePlugins.moduleRelative(xjb, baseDir));
            String encoding = PluginFacts.child(config, "encoding");
            if (encoding != null) values.put("encoding", encoding);
            if (EnvValues.parseBool(PluginFacts.child(config, "extension")).orElse(false))
                values.put("extension", true);
            if (EnvValues.parseBool(PluginFacts.child(config, "noPackageLevelAnnotations"))
                    .orElse(false)) {
                arguments.add("-npa");
            }
            arguments.addAll(children(config, "arguments"));
        }
        for (PluginExecution execution : plugin.getExecutions()) {
            for (String goal : execution.getGoals()) {
                if (goal.startsWith("schemagen")) {
                    report.warning("`" + MOJOHAUS + "` goal `" + goal + "` (a schema from the classes) has no jk"
                            + " step; only `xjc` maps to `[jaxb]`.");
                } else if (goal.equals("testXjc")) {
                    report.warning("`" + MOJOHAUS + "` goal `testXjc` generates for the test compile; `[jaxb]`"
                            + " generates into the main compile.");
                }
            }
        }
        return finish(MOJOHAUS, values, src, bindings, arguments, output, report);
    }

    private static Mapped jvnet(String artifact, Plugin plugin, @Nullable Path baseDir, ImportReport.Builder report) {
        Map<String, Object> values = new LinkedHashMap<>();
        String src = "src/main/resources";
        String output = "target/generated-sources/xjc";
        List<String> bindings = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            String declaredSrc = PluginFacts.child(config, "schemaDirectory");
            if (declaredSrc != null) src = SourceTreePlugins.moduleRelative(declaredSrc, baseDir);
            String declaredOutput = PluginFacts.child(config, "generateDirectory");
            if (declaredOutput != null) output = SourceTreePlugins.moduleRelative(declaredOutput, baseDir);
            String pkg = PluginFacts.child(config, "generatePackage");
            if (pkg != null) values.put("package", pkg);
            String bindingDir = PluginFacts.child(config, "bindingDirectory");
            if (bindingDir != null) bindings.add(SourceTreePlugins.moduleRelative(bindingDir, baseDir));
            String encoding = PluginFacts.child(config, "encoding");
            if (encoding != null) values.put("encoding", encoding);
            if (EnvValues.parseBool(PluginFacts.child(config, "extension")).orElse(false))
                values.put("extension", true);
            arguments.addAll(children(config, "args"));
            if (config.getChild("plugins") != null) {
                report.warning("`" + artifact + "` `<plugins>` (xjc plugins on the compiler's classpath) have no"
                        + " `[jaxb]` key; the preset runs xjc with its own closure.");
            }
        }
        return finish(artifact, values, src, bindings, arguments, output, report);
    }

    private static Mapped finish(
            String artifact,
            Map<String, Object> values,
            String src,
            List<String> bindings,
            List<String> arguments,
            String output,
            ImportReport.Builder report) {
        Map<String, Object> table = new LinkedHashMap<>();
        if (!src.equals(DEFAULT_SRC)) table.put("src", src);
        table.putAll(values);
        if (!bindings.isEmpty()) table.put("bindings", bindings);
        if (!arguments.isEmpty()) table.put("arguments", arguments);
        report.warning("`" + artifact + "` is `[jaxb]`: the classes are generated into the compile by xjc from the"
                + " schemas under `" + src + "`; the module keeps `jakarta.xml.bind:jakarta.xml.bind-api` for the"
                + " compile and a JAXB runtime (`org.glassfish.jaxb:jaxb-runtime`) for run time.");
        return new Mapped(new PluginConfig("jaxb", table), Map.of(output, ADD_SOURCE_ROW));
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
