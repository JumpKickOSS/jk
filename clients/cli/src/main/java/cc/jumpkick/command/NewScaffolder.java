// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.model.ToolDefaults;
import cc.jumpkick.scaffold.NewInputs;
import cc.jumpkick.scaffold.NewJkBuildRenderer;
import cc.jumpkick.scaffold.NewScaffolder.FrameworkScaffoldSource;
import cc.jumpkick.scaffold.NewScaffolder.ScaffoldFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * CLI facade over {@link cc.jumpkick.scaffold.NewScaffolder}: wires framework scaffolds through
 * {@link ExportSupport} so {@code jk new --spring} (etc.) still hits the engine generate path.
 */
public final class NewScaffolder {

    /** @see cc.jumpkick.scaffold.NewScaffolder#CURATED_DEPS */
    public static final Map<String, List<cc.jumpkick.scaffold.NewScaffolder.CuratedEntry>> CURATED_DEPS =
            cc.jumpkick.scaffold.NewScaffolder.CURATED_DEPS;

    public record CuratedEntry(String coord, String version, String scope) {
        public CuratedEntry {
            // mirror record for wizard/tests that reference command.NewScaffolder.CuratedEntry
        }
    }

    private NewScaffolder() {}

    public static void write(NewInputs inputs) throws IOException {
        write(inputs, true);
    }

    public static void write(NewInputs inputs, boolean standalone) throws IOException {
        cc.jumpkick.scaffold.NewScaffolder.write(inputs, standalone, CLI_FRAMEWORK);
    }

    /** ExportSupport-backed framework scaffold (CLI only). */
    private static final FrameworkScaffoldSource CLI_FRAMEWORK = NewScaffolder::pluginScaffold;

    private static ScaffoldFiles pluginScaffold(NewInputs inputs) throws IOException {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("plugin", inputs.frameworkPluginFlag());
        params.put(
                "lang",
                switch (inputs.lang()) {
                    case KOTLIN -> "kotlin";
                    case GROOVY -> "groovy";
                    case JAVA -> "java";
                });
        params.put("package", inputs.group());
        params.put("group", inputs.group());
        params.put("name", inputs.name());
        params.put("version", "0.1.0");
        params.putIfAbsent("quarkus.version", ToolDefaults.QUARKUS_PLATFORM_VERSION);
        params.put("simpleLayout", String.valueOf(inputs.isSimpleLayout()));
        params.put("sample", String.valueOf(inputs.sample()));
        params.put("baseToml", NewJkBuildRenderer.render(inputs));
        var files = ExportSupport.generate(inputs.directory(), "scaffold", params, "jk new", null);
        if (files == null) throw new IOException("jk new: plugin scaffold failed");
        return new ScaffoldFiles(files.paths(), files.contents());
    }
}
