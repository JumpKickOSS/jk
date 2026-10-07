// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Resource;
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
        collect(build.getResources(), MAIN_RESOURCES, "`<resources>`", baseDir, dirs, filtered, report);
        collect(build.getTestResources(), TEST_RESOURCES, "`<testResources>`", baseDir, testDirs, testFiltered, report);
        Set<String> dollar = new TreeSet<>();
        Set<String> at = new TreeSet<>();
        for (String rel : concat(filtered, testFiltered)) referenced(baseDir.resolve(rel), dollar, at);
        Map<String, String> properties = new TreeMap<>();
        Set<String> unknown = new TreeSet<>();
        for (String name : concat(List.copyOf(dollar), List.copyOf(at))) {
            if (COMPUTED.contains(name) || properties.containsKey(name)) continue;
            String value = resolve(model, name);
            if (value != null) {
                properties.put(name, value);
            } else if (dollar.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            report.warning("filtered resources reference "
                    + String.join(
                            ", ", unknown.stream().map(n -> "${" + n + "}").toList())
                    + ", which the POM does not define (environment, settings or system properties under Maven) —"
                    + " set each under [resources.properties], or the file keeps the reference as written.");
        }
        return new BuildBlock.Resources(dirs, filtered, testDirs, testFiltered, properties);
    }

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
                unsupported.add("the module directory itself (`${basedir}`)");
                continue;
            }
            if (!resource.isFiltering() && underLayoutAtItsOwnPath(dir, layoutDir, resource.getTargetPath())) continue;
            if (!resource.getIncludes().isEmpty() || !resource.getExcludes().isEmpty()) {
                unsupported.add("`<includes>`/`<excludes>` on " + dir);
            }
            if (resource.getTargetPath() != null && !resource.getTargetPath().isBlank()) {
                unsupported.add("`<targetPath>` on " + dir);
            }
            if (resource.isFiltering()) {
                if (!filtered.contains(dir)) filtered.add(dir);
            } else if (!dir.equals(layoutDir) && !dirs.contains(dir)) {
                dirs.add(dir);
            }
        }
        if (!unsupported.isEmpty()) {
            report.warning(element + " " + String.join(", ", unsupported)
                    + " is not carried into [resources]: jk copies every file of a root to the classpath root.");
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

    /** The {@code ${name}} and {@code @name@} references of the filterable files under {@code root}. */
    static void referenced(Path root, Set<String> dollar, Set<String> at) {
        List<Path> files = new ArrayList<>();
        try {
            PathUtil.forEachRegularFile(root, (file, attrs) -> files.add(file));
            for (Path file : files) {
                if (!ResourceFilter.filters(String.valueOf(file.getFileName()))) continue;
                String text = ResourceFilter.utf8(Files.readAllBytes(file));
                if (text == null) continue;
                Set<String> named = ResourceFilter.expand(text, Map.of()).unresolved();
                dollar.addAll(named);
                for (String n : ResourceFilter.references(text)) if (!named.contains(n)) at.add(n);
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
