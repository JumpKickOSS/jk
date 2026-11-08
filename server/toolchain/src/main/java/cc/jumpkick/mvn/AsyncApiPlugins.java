// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.AsyncApiTable;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * The AsyncAPI code generators as {@code [asyncapi]} ({@link AsyncApiTable}): sngular's {@code
 * scs-multiapi-maven-plugin}, whose {@code asyncapi-generation} executions name {@code
 * <specFiles><specFile><filePath>} and a {@code <modelPackage>} under {@code <consumer>}, {@code
 * <supplier>} or {@code <streamBridge>}; and ZenWave's {@code zenwave-sdk-maven-plugin}, whose
 * executions name an {@code <inputSpec>}, a {@code <generatorName>} ({@code jsonschema2pojo}
 * generates the models, any other code) and {@code <configOptions><modelPackage>}.
 */
final class AsyncApiPlugins {

    static final String SCS = "scs-multiapi-maven-plugin";
    static final String ZENWAVE = "zenwave-sdk-maven-plugin";

    /** What an {@code add-source} root inside scs-multiapi's output is, for the build-helper row. */
    static final String ADD_SOURCE_ROW = "the [asyncapi] output; no extra-src needed";

    private static final String SCS_OUTPUT = "target/generated-sources/apigenerator";

    /** The table (null when no document is in the tree) and the output roots the plugins fill. */
    record Mapped(@Nullable PluginConfig table, Map<String, String> outputRoots) {}

    private AsyncApiPlugins() {}

    static Mapped map(Model model, ImportReport.Builder report) {
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        List<AsyncApiTable.Spec> scs = new ArrayList<>();
        Plugin scsPlugin = PluginFacts.plugin(model, SCS).orElse(null);
        if (scsPlugin != null) {
            for (Xpp3Dom config : configs(scsPlugin, "asyncapi-generation")) {
                Xpp3Dom files = config.getChild("specFiles");
                if (files == null) continue;
                for (Xpp3Dom spec : files.getChildren("specFile")) scs.add(scsSpec(spec, baseDir));
            }
        }
        List<AsyncApiTable.Spec> zenwave = new ArrayList<>();
        Plugin zenwavePlugin = PluginFacts.plugin(model, ZENWAVE).orElse(null);
        if (zenwavePlugin != null) {
            for (Xpp3Dom config : configs(zenwavePlugin, "generate")) {
                String input = PluginFacts.child(config, "inputSpec");
                if (input == null) continue;
                String generator = PluginFacts.child(config, "generatorName");
                String pkg = PluginFacts.child(config.getChild("configOptions"), "modelPackage");
                zenwave.add(new AsyncApiTable.Spec(
                        SourceTreePlugins.moduleRelative(input, baseDir),
                        pkg == null ? List.of() : List.of(pkg),
                        !"jsonschema2pojo".equals(generator),
                        null));
            }
        }
        PluginConfig table = null;
        if (!scs.isEmpty()) table = AsyncApiTable.of(SCS, scs, report);
        if (table == null && !zenwave.isEmpty()) table = AsyncApiTable.of(ZENWAVE, zenwave, report);
        return new Mapped(table, scsPlugin == null ? Map.of() : Map.of(SCS_OUTPUT, ADD_SOURCE_ROW));
    }

    private static AsyncApiTable.Spec scsSpec(Xpp3Dom spec, @Nullable Path baseDir) {
        String file = PluginFacts.child(spec, "filePath");
        List<String> packages = new ArrayList<>();
        boolean code = false;
        for (String role : List.of("consumer", "supplier", "streamBridge")) {
            Xpp3Dom block = spec.getChild(role);
            if (block == null) continue;
            code = true;
            String pkg = PluginFacts.child(block, "modelPackage");
            if (pkg != null) packages.add(pkg);
        }
        if (EnvValues.parseBool(PluginFacts.child(spec, "generateModelOnly")).orElse(false)) code = false;
        return new AsyncApiTable.Spec(
                file == null ? null : SourceTreePlugins.moduleRelative(file, baseDir),
                packages,
                code,
                PluginFacts.child(spec, "fromArtifactId"));
    }

    /** Each execution's configuration that runs {@code goal}, over the plugin's own. */
    private static List<Xpp3Dom> configs(Plugin plugin, String goal) {
        Xpp3Dom shared = plugin.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
        List<Xpp3Dom> out = new ArrayList<>();
        for (PluginExecution execution : plugin.getExecutions()) {
            if (!execution.getGoals().contains(goal)) continue;
            Xpp3Dom own = execution.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
            Xpp3Dom merged =
                    own == null ? shared : shared == null ? own : Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(own), shared);
            if (merged != null) out.add(merged);
        }
        return out;
    }
}
