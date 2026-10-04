// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code maven-hpi-plugin}'s {@code generate-taglib-interface} goal is the {@code [taglib]}
 * preset: the POM's resource directories are {@code resources}, and the goal's
 * {@code <outputDirectory>} is the preset's own contribution, so an {@code add-source} root inside
 * it is not written as an {@code extra-src}. Its {@code resolve-test-dependencies} goal is
 * {@code [jenkins-test]}, which installs the test closure's Jenkins plugins for JenkinsRule. Any
 * other goal is a row of its own.
 */
final class TaglibPlugin {

    static final String ARTIFACT = "maven-hpi-plugin";
    static final String GOAL = "generate-taglib-interface";
    static final String TEST_GOAL = "resolve-test-dependencies";

    /** What an {@code add-source} root inside the output is, for the build-helper row. */
    static final String ADD_SOURCE_ROW = "the taglib preset's output; `[taglib]` folds the generated tag-library"
            + " interfaces into the compile itself, so no `extra-src` root is written";

    private static final String DEFAULT_OUTPUT = "target/generated-sources/taglib-interface";
    private static final String MAIN_RESOURCES = "src/main/resources";

    /**
     * The taglib table (null without its goal), the {@code [jenkins-test]} table (null without
     * {@value #TEST_GOAL}), the output root the taglib fills, the plugins they consumed.
     */
    record Mapped(
            @Nullable PluginConfig table,
            @Nullable PluginConfig testPlugins,
            Map<String, String> outputRoots,
            Set<String> consumed) {
        static final Mapped NONE = new Mapped(null, null, Map.of(), Set.of());
    }

    private TaglibPlugin() {}

    static Mapped map(Model model, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, ARTIFACT).orElse(null);
        if (plugin == null) return Mapped.NONE;
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        String output = null;
        boolean testPlugins = false;
        List<String> otherGoals = new ArrayList<>();
        for (PluginExecution execution : plugin.getExecutions()) {
            for (String goal : execution.getGoals()) {
                if (TEST_GOAL.equals(goal)) {
                    testPlugins = true;
                    continue;
                }
                if (!GOAL.equals(goal)) {
                    if (!otherGoals.contains(goal)) otherGoals.add(goal);
                    continue;
                }
                output = DEFAULT_OUTPUT;
                Xpp3Dom config = execution.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
                String declared = PluginFacts.child(config, "outputDirectory");
                if (declared != null) output = SourceTreePlugins.moduleRelative(declared, baseDir);
            }
        }
        if (output == null && !testPlugins) return Mapped.NONE;
        PluginConfig taglib = null;
        if (output != null) {
            Map<String, Object> values = new LinkedHashMap<>();
            List<String> resourceDirs = LocalizerPlugin.resourceDirs(model, baseDir);
            if (!resourceDirs.equals(List.of(MAIN_RESOURCES))) values.put("resources", resourceDirs);
            report.warning("`" + ARTIFACT + "` `" + GOAL + "` is `[taglib]`: the typed tag-library interfaces are"
                    + " generated into the compile from the tag libraries under " + String.join(", ", resourceDirs)
                    + "; the module's `org.kohsuke.stapler:stapler-groovy` dependency stays, the interfaces read it"
                    + " at compile time.");
            taglib = new PluginConfig("taglib", values);
        }
        if (testPlugins) {
            report.warning("`" + ARTIFACT + "` `" + TEST_GOAL + "` is `[jenkins-test]`: the Jenkins plugins of the"
                    + " test closure are installed under test-dependencies/ on the test classpath, where JenkinsRule"
                    + " loads them.");
        }
        for (String goal : otherGoals) {
            report.warning("`" + ARTIFACT + "` goal `" + goal + "` was not imported; only `" + GOAL + "` and `"
                    + TEST_GOAL + "` map to jk tables.");
        }
        return new Mapped(
                taglib,
                testPlugins ? new PluginConfig("jenkins-test", Map.of()) : null,
                output == null ? Map.of() : Map.of(output, ADD_SOURCE_ROW),
                Set.of(ARTIFACT));
    }
}
