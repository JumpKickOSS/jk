// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.JavadocMode;
import cc.jumpkick.model.SourcesMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Resource;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * The plugins that shape the source tree rather than the classpath. {@code build-helper-maven-plugin}
 * {@code add-source} / {@code add-test-source} become {@code [build] extra-src} / {@code [test]
 * extra-src}; {@code maven-source-plugin} is {@code sources = "always"}, the sources jar Maven built
 * on every package; {@code maven-javadoc-plugin} is satisfied by the javadoc jar a library ships by
 * default, and becomes {@code javadoc = "strict"} when its configuration keeps doclint on and fails
 * on error — {@code <doclint>none</doclint>} turns doclint off, so that plugin stays lenient. An
 * {@code add-source} root inside a generator's output directory is that generator's contribution,
 * not an extra root. Resource filtering has no jk equivalent, so a filtered directory is a report
 * row, as is a resource directory outside the fixed layout. Everything else under
 * {@code <build><plugins>} gets the generic row.
 */
final class SourceTreePlugins {

    /** The source-tree settings a POM's plugins add to the module. */
    record SourceTree(List<String> extraSrc, List<String> testExtraSrc, SourcesMode sources, JavadocMode javadoc) {}

    private static final String MAIN_RESOURCES = "src/main/resources";
    private static final String TEST_RESOURCES = "src/test/resources";

    private SourceTreePlugins() {}

    /**
     * {@code generatorOutputs} are the module-relative directories the module's generators fill,
     * each with what an {@code add-source} root inside it is. In a workspace ({@code inherited}
     * set) the generic row for a plugin the POM inherits is counted onto the declaring POM's row
     * rather than said at every module; the root's own go there too.
     */
    static SourceTree map(
            EffectiveModel em,
            Map<String, String> generatorOutputs,
            ImportReport.Builder report,
            @Nullable InheritedRows inherited,
            boolean isRoot) {
        Model model = em.model();
        List<String> extraSrc = new ArrayList<>();
        List<String> testExtraSrc = new ArrayList<>();
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        PluginFacts.plugin(model, "build-helper-maven-plugin")
                .ifPresent(helper -> addSourceRoots(helper, baseDir, generatorOutputs, extraSrc, testExtraSrc, report));
        reportResources(model, report);
        SourcesMode sources = PluginFacts.plugin(model, "maven-source-plugin").isPresent()
                ? SourcesMode.ALWAYS
                : SourcesMode.DISABLED;
        JavadocMode javadoc = PluginFacts.plugin(model, "maven-javadoc-plugin")
                .filter(SourceTreePlugins::failsOnDoclint)
                .map(p -> JavadocMode.STRICT)
                .orElse(JavadocMode.LENIENT);
        reportUnmappedPlugins(em, report, inherited, isRoot);
        return new SourceTree(extraSrc, testExtraSrc, sources, javadoc);
    }

    /**
     * Whether the plugin was told to fail the build on a malformed comment: {@code <doclint>} set to
     * anything but {@code none}, or {@code <failOnError>true</failOnError>}. An explicit
     * {@code <failOnError>false</failOnError>} is lenient whatever doclint says, and
     * {@code <doclint>none</doclint>} is the plugin turning doclint off, which is jk's default.
     */
    private static boolean failsOnDoclint(Plugin javadoc) {
        boolean strict = false;
        for (Xpp3Dom config : PluginFacts.configurations(javadoc)) {
            String failOnError = PluginFacts.usable(text(config.getChild("failOnError")));
            if (failOnError != null && !EnvValues.parseBool(failOnError).orElse(true)) return false;
            if (failOnError != null && EnvValues.parseBool(failOnError).orElse(false)) strict = true;
            String doclint = PluginFacts.usable(text(config.getChild("doclint")));
            if (doclint != null && !"none".equalsIgnoreCase(doclint.trim())) strict = true;
        }
        return strict;
    }

    private static @Nullable String text(@Nullable Xpp3Dom node) {
        return node == null ? null : node.getValue();
    }

    /**
     * Each {@code add-source} / {@code add-test-source} execution's {@code <sources>}; other goals are
     * a row. A root under a generator's output is the generator's own contribution and is not written.
     */
    private static void addSourceRoots(
            Plugin helper,
            @Nullable Path baseDir,
            Map<String, String> generatorOutputs,
            List<String> extraSrc,
            List<String> testExtraSrc,
            ImportReport.Builder report) {
        for (PluginExecution execution : helper.getExecutions()) {
            Xpp3Dom config = execution.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
            for (String goal : execution.getGoals()) {
                switch (goal) {
                    case "add-source" ->
                        extraSrc.addAll(ownRoots(sourceDirs(config, baseDir), generatorOutputs, report));
                    case "add-test-source" ->
                        testExtraSrc.addAll(ownRoots(sourceDirs(config, baseDir), generatorOutputs, report));
                    default ->
                        report.warning("`build-helper-maven-plugin` goal `" + goal
                                + "` was not imported; only `add-source` and `add-test-source` map to"
                                + " source roots.");
                }
            }
        }
    }

    /** {@code dirs} without the ones inside a generator's output, each of those being a row. */
    private static List<String> ownRoots(
            List<String> dirs, Map<String, String> generatorOutputs, ImportReport.Builder report) {
        List<String> own = new ArrayList<>();
        for (String dir : dirs) {
            String output = generatorOutputs.keySet().stream()
                    .filter(root -> dir.equals(root) || dir.startsWith(root + "/"))
                    .findFirst()
                    .orElse(null);
            if (output == null) {
                own.add(dir);
                continue;
            }
            report.warning("`build-helper-maven-plugin` adds `" + dir + "`, " + generatorOutputs.get(output) + ".");
        }
        return own;
    }

    private static List<String> sourceDirs(@Nullable Xpp3Dom config, @Nullable Path baseDir) {
        List<String> dirs = new ArrayList<>();
        Xpp3Dom sources = config == null ? null : config.getChild("sources");
        if (sources == null) return dirs;
        for (Xpp3Dom source : sources.getChildren()) {
            String value = source.getValue();
            if (value == null || value.isBlank()) continue;
            dirs.add(moduleRelative(value.trim(), baseDir));
        }
        return dirs;
    }

    /**
     * A source directory as the manifest wants it: module-relative. {@code ${project.basedir}/x} and
     * {@code ${basedir}/x} lose their prefix; an absolute path under the POM's directory is
     * relativized to it.
     */
    static String moduleRelative(String dir, @Nullable Path baseDir) {
        String d = dir.replace('\\', '/');
        for (String prefix : new String[] {"${project.basedir}/", "${basedir}/", "${project.basedir}", "${basedir}"}) {
            if (d.startsWith(prefix)) return stripLeadingSlashes(d.substring(prefix.length()));
        }
        if (baseDir != null) {
            Path path = Path.of(d);
            if (path.isAbsolute() && path.startsWith(baseDir.toAbsolutePath())) {
                return baseDir.toAbsolutePath().relativize(path).toString().replace('\\', '/');
            }
        }
        return d;
    }

    private static String stripLeadingSlashes(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '/') i++;
        return s.substring(i);
    }

    /**
     * A {@code <resource>} that filters, or lives outside {@code src/main/resources} /
     * {@code src/test/resources}, is a row: jk copies resources from the fixed layout as written.
     */
    private static void reportResources(Model model, ImportReport.Builder report) {
        Build build = model.getBuild();
        if (build == null) return;
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        reportResources(build.getResources(), MAIN_RESOURCES, "`<resources>`", baseDir, report);
        reportResources(build.getTestResources(), TEST_RESOURCES, "`<testResources>`", baseDir, report);
    }

    private static void reportResources(
            List<Resource> resources,
            String layoutDir,
            String element,
            @Nullable Path baseDir,
            ImportReport.Builder report) {
        List<String> filtered = new ArrayList<>();
        List<String> elsewhere = new ArrayList<>();
        for (Resource resource : resources) {
            String raw = resource.getDirectory();
            String dir = raw == null || raw.isBlank() ? layoutDir : moduleRelative(raw.trim(), baseDir);
            if (resource.isFiltering()) filtered.add(dir);
            if (!dir.equals(layoutDir)) elsewhere.add(dir);
        }
        if (!filtered.isEmpty()) {
            report.warning(element + " with `<filtering>true</filtering>` on " + String.join(", ", filtered)
                    + " — jk has no resource filtering; `${...}` placeholders in those files are copied as"
                    + " written. Read the values at runtime or check the filled-in file in.");
        }
        if (!elsewhere.isEmpty()) {
            report.warning(element + " directory " + String.join(", ", elsewhere) + " is outside `" + layoutDir
                    + "` — jk's layout reads `" + layoutDir + "` only; move the files there.");
        }
    }

    /** Every declared plugin the import has no mapping for; the migration page says where each lands. */
    private static void reportUnmappedPlugins(
            EffectiveModel em, ImportReport.Builder report, @Nullable InheritedRows inherited, boolean isRoot) {
        for (Plugin plugin : PluginFacts.plugins(em.model())) {
            String artifactId = plugin.getArtifactId();
            if (artifactId == null || PluginFacts.MAPPED_PLUGINS.contains(artifactId)) continue;
            String message = "`<plugin>" + artifactId
                    + "</plugin>` was not imported; docs/user/migration.md lists where it lands in jk.";
            boolean own = InheritedRows.declaresPlugin(em.raw(), artifactId, em.activeProfiles());
            if (inherited == null || (!isRoot && own)) {
                report.warning(message);
            } else if (isRoot) {
                inherited.declaredByRoot(ImportReport.Severity.WARNING, message);
            } else {
                inherited.inherited(
                        inherited.declaredBy(em, raw -> InheritedRows.declaresPlugin(raw, artifactId)),
                        ImportReport.Severity.WARNING,
                        message);
            }
        }
    }
}
