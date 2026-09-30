// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.ReleaseSources;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * A compiler-plugin execution with {@code <multiReleaseOutput>true</multiReleaseOutput>} compiles its
 * {@code <compileSourceRoots>} at its {@code <release>} into {@code META-INF/versions/<release>/}:
 * that is a {@code [multi-release]} entry. Executions of a profile Maven activates on this machine
 * (a JDK-range {@code multi-release} profile) are in the effective model and map the same way.
 */
final class MultiReleasePlugins {

    private MultiReleasePlugins() {}

    static List<ReleaseSources> map(Model model, ImportReport.Builder report) {
        Optional<Plugin> compiler = PluginFacts.compilerPlugin(model);
        if (compiler.isEmpty()) return List.of();
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        Map<Integer, Set<String>> byRelease = new TreeMap<>();
        for (PluginExecution execution : compiler.get().getExecutions()) {
            if (!(execution.getConfiguration() instanceof Xpp3Dom config)) continue;
            boolean multiRelease = EnvValues.parseBool(
                            PluginFacts.usable(PluginFacts.text(config.getChild("multiReleaseOutput"))))
                    .orElse(false);
            if (!multiRelease) continue;
            String label = "`maven-compiler-plugin` execution `" + execution.getId() + "`";
            Integer release = release(config);
            if (release == null) {
                report.warning(label + " writes multi-release output but names no `<release>`; add a"
                        + " `[multi-release]` entry for its release by hand.");
                continue;
            }
            if (release < ReleaseSources.FLOOR) {
                report.warning(label + " writes multi-release output for Java " + release + ", below the "
                        + ReleaseSources.FLOOR + " a JDK reads `META-INF/versions/` from; not written.");
                continue;
            }
            List<String> roots = roots(config.getChild("compileSourceRoots"), baseDir);
            if (roots.isEmpty()) {
                report.warning(label + " writes multi-release output for Java " + release
                        + " from the main source root; jk compiles that root once, so move the release's sources"
                        + " to their own directory and name it under `[multi-release]`.");
                continue;
            }
            byRelease.computeIfAbsent(release, r -> new LinkedHashSet<>()).addAll(roots);
        }
        List<ReleaseSources> out = new ArrayList<>();
        byRelease.forEach((release, roots) -> out.add(new ReleaseSources(release, List.copyOf(roots))));
        return out;
    }

    private static @Nullable Integer release(Xpp3Dom config) {
        return PluginFacts.configuredLevel(config).orElse(null);
    }

    private static List<String> roots(@Nullable Xpp3Dom list, @Nullable Path baseDir) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        for (Xpp3Dom child : list.getChildren()) {
            String value = PluginFacts.usable(PluginFacts.text(child));
            if (value != null) out.add(SourceTreePlugins.moduleRelative(value.trim(), baseDir));
        }
        return out;
    }
}
