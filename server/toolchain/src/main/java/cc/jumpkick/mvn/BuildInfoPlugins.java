// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * Git build info is the {@code [build-info]} table: both git-commit-id plugin ids write {@code
 * git.properties} the way the table does, and Boot's {@code build-info} goal writes the {@code
 * build-info.properties} the table adds to a Boot module. A properties file placed under the output
 * directory keeps its name inside the jar; what jk does not reproduce — JSON output, another date
 * format, a file written outside the output directory — is a row.
 */
final class BuildInfoPlugins {

    static final String GIT_COMMIT_ID = "git-commit-id-maven-plugin";
    static final String GIT_COMMIT_ID_LEGACY = "git-commit-id-plugin";
    private static final String SPRING_BOOT = "spring-boot-maven-plugin";
    /** Boot's goal shares the table's name, which is what the step is called too. */
    private static final String BOOT_GOAL = TaskNames.BUILD_INFO;

    /** The plugin's own default; jk spells its timestamps the same way. */
    private static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZ";

    private static final String OUTPUT_DIR = "${project.build.outputDirectory}";

    private BuildInfoPlugins() {}

    static Optional<JkBuild.BuildInfo> map(EffectiveModel em, ImportReport.Builder report) {
        Optional<Plugin> git = PluginFacts.plugin(em.model(), GIT_COMMIT_ID)
                .or(() -> PluginFacts.plugin(em.model(), GIT_COMMIT_ID_LEGACY));
        if (git.isPresent()) return Optional.of(mapGitCommitId(git.get(), outputDirectory(em.model()), report));
        boolean bootGoal = PluginFacts.plugin(em.model(), SPRING_BOOT).stream()
                .flatMap(boot -> boot.getExecutions().stream())
                .map(PluginExecution::getGoals)
                .anyMatch(goals -> goals.contains(BOOT_GOAL));
        return bootGoal ? Optional.of(JkBuild.BuildInfo.DEFAULT) : Optional.empty();
    }

    /** The effective model's output directory: interpolated to an absolute path when the POM had a file. */
    private static @Nullable String outputDirectory(Model model) {
        return model.getBuild() == null
                ? null
                : PluginFacts.usable(model.getBuild().getOutputDirectory());
    }

    private static JkBuild.BuildInfo mapGitCommitId(
            Plugin plugin, @Nullable String outputDirectory, ImportReport.Builder report) {
        String id = "`" + plugin.getArtifactId() + "`";
        String file = null;
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            String format = PluginFacts.usable(PluginFacts.child(config, "format"));
            if (format != null && !"properties".equalsIgnoreCase(format)) {
                report.warning(id + " `<format>" + format + "` — `[build-info]` writes a properties file;"
                        + " a consumer parsing JSON reads the same keys from `git.properties`.");
            }
            String dateFormat = PluginFacts.usable(PluginFacts.child(config, "dateFormat"));
            if (dateFormat != null && !DEFAULT_DATE_FORMAT.equals(dateFormat)) {
                report.warning(id + " `<dateFormat>` " + dateFormat + " — `[build-info]` writes timestamps as "
                        + DEFAULT_DATE_FORMAT + ", the plugin's default and the spelling Spring Boot parses.");
            }
            String filename = PluginFacts.usable(PluginFacts.child(config, "generateGitPropertiesFilename"));
            if (filename != null) file = fileInsideJar(filename, outputDirectory, id, report);
        }
        return file == null ? JkBuild.BuildInfo.DEFAULT : new JkBuild.BuildInfo(file, false);
    }

    /**
     * The jar-relative name of the file the plugin writes: its path under the output directory, or
     * its bare name with a row when it was written elsewhere (a source tree, the base directory).
     */
    private static @Nullable String fileInsideJar(
            String filename, @Nullable String outputDirectory, String id, ImportReport.Builder report) {
        String normalized = filename.replace('\\', '/');
        for (String prefix : outputPrefixes(outputDirectory)) {
            int at = normalized.indexOf(prefix);
            if (at < 0) continue;
            String rel = stripSlashes(normalized.substring(at + prefix.length()));
            return rel.isEmpty() ? null : rel;
        }
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.isEmpty()) return null;
        report.warning(id + " `<generateGitPropertiesFilename>` " + filename + " — `[build-info]` writes the file"
                + " into the jar's root as `" + name + "`; a copy in the source tree is not maintained.");
        return name;
    }

    /** The spellings of the output directory a filename may start with: the property, its value, the default. */
    private static List<String> outputPrefixes(@Nullable String outputDirectory) {
        List<String> prefixes = new ArrayList<>(List.of(OUTPUT_DIR, "target/classes"));
        if (outputDirectory != null) prefixes.add(outputDirectory.replace('\\', '/'));
        return prefixes;
    }

    private static String stripSlashes(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') start++;
        return path.substring(start);
    }
}
