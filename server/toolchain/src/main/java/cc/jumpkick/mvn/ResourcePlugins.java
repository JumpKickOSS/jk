// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.ResourceFilter;
import cc.jumpkick.model.BuildBlock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Resource;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code <resources>} / {@code <testResources>} as {@code [resources]}: a directory outside the
 * layout is an extra root, a filtering one a filtered root, and every name its files reference is
 * written into {@code [resources.properties]} with the value the effective POM gives it, so the
 * build needs no Maven. Names jk knows itself are not written; names only Maven's runtime knows
 * (environment, settings, system properties) are a row. Only directories that exist are carried:
 * a parent's {@code <resources>} reaches every module, and most have no such directory.
 */
final class ResourcePlugins {

    private static final String MAIN_RESOURCES = "src/main/resources";
    private static final String TEST_RESOURCES = "src/test/resources";

    /** What jk sets itself when it filters; the import leaves them out. */
    private static final Set<String> COMPUTED = Set.of("project.groupId", "project.artifactId", "project.version");

    private ResourcePlugins() {}

    static BuildBlock.Resources map(Model model, ImportReport.Builder report) {
        Build build = model.getBuild();
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        if (build == null || baseDir == null) return BuildBlock.Resources.EMPTY;
        List<String> dirs = new ArrayList<>();
        List<String> filtered = new ArrayList<>();
        List<String> testDirs = new ArrayList<>();
        List<String> testFiltered = new ArrayList<>();
        collect(build.getResources(), MAIN_RESOURCES, "<resources>", baseDir, dirs, filtered, report);
        collect(build.getTestResources(), TEST_RESOURCES, "<testResources>", baseDir, testDirs, testFiltered, report);
        List<String> delimiters = delimiters(model, report);
        ResourceFilter filter = ResourceFilter.of(delimiters);
        Set<String> reported = new TreeSet<>();
        Set<String> named = new TreeSet<>();
        for (String rel : concat(filtered, testFiltered)) referenced(baseDir.resolve(rel), filter, reported, named);
        Map<String, String> properties = new TreeMap<>();
        Set<String> unknown = new TreeSet<>();
        for (String name : named) {
            if (COMPUTED.contains(name)) continue;
            String value = resolve(model, name);
            if (value != null) {
                properties.put(name, value);
            } else if (reported.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            report.warning("filtered resources: " + String.join(", ", unknown)
                    + " undefined; set them in [resources.properties]");
        }
        return new BuildBlock.Resources(dirs, filtered, testDirs, testFiltered, properties, delimiters);
    }

    /**
     * The delimiters maven-resources-plugin filters with, in jk's spelling, which is Maven's: {@code
     * ${*}} and {@code @} unless {@code <useDefaultDelimiters>} is false, then each {@code
     * <delimiter>} not already among them ({@code @*@} is {@code @}). One jk cannot filter with is
     * left out with a warning.
     */
    static List<String> delimiters(Model model, ImportReport.Builder report) {
        Optional<Plugin> plugin = PluginFacts.lifecycleBoundPlugin(model, RESOURCES_PLUGIN);
        if (plugin.isEmpty() || !(plugin.get().getConfiguration() instanceof Xpp3Dom config)) {
            return BuildBlock.Resources.DEFAULT_DELIMITERS;
        }
        boolean defaults = EnvValues.parseBool(
                        PluginFacts.usable(PluginFacts.text(config.getChild("useDefaultDelimiters"))))
                .orElse(true);
        Set<String> delimiters = new LinkedHashSet<>(defaults ? BuildBlock.Resources.DEFAULT_DELIMITERS : List.of());
        Xpp3Dom list = config.getChild("delimiters");
        if (list != null) {
            for (Xpp3Dom child : list.getChildren()) {
                String d = PluginFacts.usable(PluginFacts.text(child));
                if (d == null) continue;
                String delimiter = d.trim();
                if (delimiter.length() > 2
                        && delimiter.indexOf('*') == delimiter.length() / 2
                        && delimiter
                                .substring(0, delimiter.length() / 2)
                                .equals(delimiter.substring(delimiter.length() / 2 + 1))) {
                    delimiter = delimiter.substring(0, delimiter.length() / 2);
                }
                String problem = ResourceFilter.problem(delimiter);
                if (problem != null) {
                    report.warning("maven-resources-plugin <delimiter> " + delimiter + ": not imported; " + problem);
                } else {
                    delimiters.add(delimiter);
                }
            }
        }
        return List.copyOf(delimiters);
    }

    private static final String RESOURCES_PLUGIN = "maven-resources-plugin";

    private static void collect(
            List<Resource> resources,
            String layoutDir,
            String element,
            Path baseDir,
            List<String> dirs,
            List<String> filtered,
            ImportReport.Builder report) {
        Set<String> unsupported = new LinkedHashSet<>();
        for (Resource resource : resources) {
            String raw = resource.getDirectory();
            String dir =
                    raw == null || raw.isBlank() ? layoutDir : SourceTreePlugins.moduleRelative(raw.trim(), baseDir);
            if (dir.startsWith("/") || dir.contains("..") || !Files.isDirectory(baseDir.resolve(dir))) continue;
            if (dir.isEmpty()) {
                // The module directory itself: as a root it would put the sources and the POM on the
                // classpath. Maven copies only what its includes name.
                unsupported.add("the module directory (${basedir})");
                continue;
            }
            if (!resource.isFiltering() && underLayoutAtItsOwnPath(dir, layoutDir, resource.getTargetPath())) continue;
            if (!resource.getIncludes().isEmpty() || !resource.getExcludes().isEmpty()) {
                unsupported.add("<includes>/<excludes> on " + dir);
            }
            if (resource.getTargetPath() != null && !resource.getTargetPath().isBlank()) {
                unsupported.add("<targetPath> on " + dir);
            }
            if (resource.isFiltering()) {
                if (!filtered.contains(dir)) filtered.add(dir);
            } else if (!dir.equals(layoutDir) && !dirs.contains(dir)) {
                dirs.add(dir);
            }
        }
        if (!unsupported.isEmpty()) {
            report.warning(element + " " + String.join(", ", unsupported)
                    + ": not imported; [resources] copies whole roots to the classpath root");
        }
    }

    /**
     * A directory inside the layout's resource root whose {@code <targetPath>} is its own path there:
     * the layout root already copies its files to that place, so it is no root of its own.
     */
    static boolean underLayoutAtItsOwnPath(String dir, String layoutDir, @Nullable String targetPath) {
        if (targetPath == null || !dir.startsWith(layoutDir + "/")) return false;
        String target = targetPath.strip().replace('\\', '/');
        while (target.endsWith("/")) target = target.substring(0, target.length() - 1);
        return target.equals(dir.substring(layoutDir.length() + 1));
    }

    /**
     * The references of the filterable files under {@code root} through {@code filter}'s delimiters:
     * every name into {@code named}, those whose delimiter reports an unresolved name also into
     * {@code reported}.
     */
    static void referenced(Path root, ResourceFilter filter, Set<String> reported, Set<String> named) {
        List<Path> files = new ArrayList<>();
        try {
            PathUtil.forEachRegularFile(root, (file, attrs) -> files.add(file));
            for (Path file : files) {
                if (!ResourceFilter.filters(String.valueOf(file.getFileName()))) continue;
                String text = ResourceFilter.utf8(Files.readAllBytes(file));
                if (text == null) continue;
                reported.addAll(filter.expand(text, Map.of()).unresolved());
                named.addAll(filter.references(text));
            }
        } catch (IOException e) {
            // an unreadable file contributes no names; the build reports what stays unexpanded
        }
    }

    /** The effective POM's value for {@code name}: a property, else a project field Maven exposes. */
    static @Nullable String resolve(Model model, String name) {
        String property = model.getProperties().getProperty(name);
        if (property != null) return property;
        String field = name.startsWith("pom.") ? "project." + name.substring(4) : name;
        return switch (field) {
            case "project.name" -> model.getName();
            case "project.description" -> model.getDescription();
            case "project.url" -> model.getUrl();
            case "project.inceptionYear" -> model.getInceptionYear();
            case "project.packaging" -> model.getPackaging();
            case "project.organization.name" ->
                model.getOrganization() == null ? null : model.getOrganization().getName();
            case "project.build.finalName" ->
                model.getBuild() == null ? null : model.getBuild().getFinalName();
            case "project.parent.version" ->
                model.getParent() == null ? null : model.getParent().getVersion();
            case "project.parent.groupId" ->
                model.getParent() == null ? null : model.getParent().getGroupId();
            case "project.parent.artifactId" ->
                model.getParent() == null ? null : model.getParent().getArtifactId();
            default -> null;
        };
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
