// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.model.LibDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code maven-dependency-plugin:copy-dependencies} into a directory under {@code target/}, with
 * {@code maven-jar-plugin}'s {@code addClasspath} / {@code classpathPrefix} pointing the jar at it,
 * is the {@code [application] lib-dir} layout: the directory's name as {@code lib-dir}, {@code
 * <stripVersion>} as {@code lib-versions = false}, {@code <excludeArtifactIds>} and {@code
 * <excludeGroupIds>} as {@code lib-exclude}. An option the layout does not model is a row.
 */
final class LibDirPlugins {

    static final String DEPENDENCY_PLUGIN = WirePlugin.DEPENDENCY_PLUGIN;

    private static final String JAR = "maven-jar-plugin";
    private static final String GOAL = "copy-dependencies";
    private static final String DEFAULT_OUTPUT = "dependency";

    /** Options that change nothing about what the directory holds, or that the layout maps. */
    private static final Set<String> COVERED = Set.of(
            "outputDirectory",
            "includeScope",
            "excludeArtifactIds",
            "excludeGroupIds",
            "stripVersion",
            "overWriteReleases",
            "overWriteSnapshots",
            "overWriteIfNewer",
            "silent",
            "useBaseVersion",
            "markersDirectory",
            "failOnMissingClassifierArtifact");

    /** The layout ({@code null} when nothing maps) and whether the dependency plugin is accounted for. */
    record Mapped(@Nullable LibDir libDir, boolean consumed) {
        static final Mapped NONE = new Mapped(null, false);
    }

    private LibDirPlugins() {}

    /**
     * The layout the module's {@code copy-dependencies} execution describes. {@code mainClass} is
     * the application main the import found; without one there is no {@code [application]} for the
     * layout to belong to, and a row says so. The plugin counts as imported only when every one of
     * its executions copies dependencies.
     */
    static Mapped map(Model model, @Nullable String mainClass, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, DEPENDENCY_PLUGIN).orElse(null);
        if (plugin == null) return Mapped.NONE;
        List<PluginExecution> copies = plugin.getExecutions().stream()
                .filter(e -> e.getGoals().contains(GOAL))
                .toList();
        if (copies.isEmpty()) return Mapped.NONE;
        boolean consumed =
                plugin.getExecutions().stream().allMatch(e -> e.getGoals().equals(List.of(GOAL)));
        PluginExecution execution = copies.getFirst();
        for (PluginExecution extra : copies.subList(1, copies.size())) {
            report.warning("`" + DEPENDENCY_PLUGIN + "` execution `" + extra.getId() + "` copies dependencies a second"
                    + " time; `[application] lib-dir` is execution `" + execution.getId() + "`'s directory alone.");
        }
        Xpp3Dom config = configuration(plugin, execution);
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        String dir = libDir(model, config, baseDir);
        if (dir == null) {
            report.warning("`" + DEPENDENCY_PLUGIN + "` execution `" + execution.getId() + "` copies dependencies"
                    + " outside the build directory; `[application] lib-dir` lays them out under target/ only, so"
                    + " it is not written.");
            return new Mapped(null, consumed);
        }
        if (mainClass == null) {
            report.warning("`" + DEPENDENCY_PLUGIN + "` execution `" + execution.getId() + "` copies the runtime"
                    + " dependencies into target/" + dir + "; jk's `lib-dir` layout belongs to an application and"
                    + " this module names no main class, so it is not written.");
            return new Mapped(null, consumed);
        }
        String scope = PluginFacts.child(config, "includeScope");
        if (scope != null && !scope.equals("runtime")) {
            report.warning("`" + DEPENDENCY_PLUGIN + "` copies `<includeScope>" + scope + "</includeScope>`"
                    + " dependencies; `[application] lib-dir` holds the runtime classpath, which is what the jar's"
                    + " Class-Path names.");
        }
        if (config != null) {
            for (Xpp3Dom option : config.getChildren()) {
                if (COVERED.contains(option.getName())) continue;
                report.warning("`" + DEPENDENCY_PLUGIN + "` `<" + option.getName() + ">` is not carried into"
                        + " `[application] lib-dir`, which copies the runtime classpath under coordinate names.");
            }
        }
        checkClassPathPrefix(model, dir, report);
        boolean versions = !Boolean.parseBoolean(PluginFacts.child(config, "stripVersion"));
        List<String> exclude = new ArrayList<>(split(PluginFacts.child(config, "excludeArtifactIds")));
        for (String group : split(PluginFacts.child(config, "excludeGroupIds"))) exclude.add(group + ":*");
        return new Mapped(new LibDir(dir, versions, exclude), consumed);
    }

    /**
     * The copy's directory relative to the build directory, or {@code null} when it lies outside.
     * Maven's default is {@code target/dependency}.
     */
    private static @Nullable String libDir(Model model, @Nullable Xpp3Dom config, @Nullable Path baseDir) {
        String output = PluginFacts.child(config, "outputDirectory");
        if (output == null) return DEFAULT_OUTPUT;
        String buildDir = model.getBuild() == null || model.getBuild().getDirectory() == null
                ? OutputDirs.TARGET
                : SourceTreePlugins.moduleRelative(model.getBuild().getDirectory(), baseDir);
        String relative = SourceTreePlugins.moduleRelative(output, baseDir);
        String prefix = trimSlashes(buildDir) + "/";
        if (!relative.startsWith(prefix)) return null;
        String dir = trimSlashes(relative.substring(prefix.length()));
        return dir.isEmpty() || dir.contains("..") ? null : dir;
    }

    /** A jar {@code Class-Path} prefix that points elsewhere than the copy is a row. */
    private static void checkClassPathPrefix(Model model, String dir, ImportReport.Builder report) {
        Plugin jar = PluginFacts.lifecycleBoundPlugin(model, JAR).orElse(null);
        if (jar == null) return;
        for (Xpp3Dom config : PluginFacts.configurations(jar)) {
            List<Xpp3Dom> manifests = new ArrayList<>();
            PluginFacts.visit(config, "manifest", manifests::add);
            for (Xpp3Dom manifest : manifests) {
                if (!Boolean.parseBoolean(PluginFacts.child(manifest, "addClasspath"))) continue;
                String raw = PluginFacts.child(manifest, "classpathPrefix");
                String prefix = raw == null ? "" : trimSlashes(raw);
                if (!prefix.equals(dir)) {
                    report.warning("`" + JAR + "` `<classpathPrefix>` is `" + (raw == null ? "" : raw) + "` but the"
                            + " dependencies are copied into target/" + dir + "; the lib-dir jar's Class-Path names"
                            + " " + dir + "/, where the jars are.");
                }
            }
        }
    }

    /** The configuration Maven runs {@code execution} with: the plugin's, overlaid by the execution's. */
    private static @Nullable Xpp3Dom configuration(Plugin plugin, PluginExecution execution) {
        Xpp3Dom own = execution.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
        Xpp3Dom shared = plugin.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
        if (own == null) return shared;
        if (shared == null) return own;
        return Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(own), new Xpp3Dom(shared));
    }

    private static List<String> split(@Nullable String csv) {
        if (csv == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String trimSlashes(String s) {
        String t = s.replace('\\', '/');
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
