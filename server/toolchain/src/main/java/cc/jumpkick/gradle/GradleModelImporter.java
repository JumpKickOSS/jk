// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.mvn.ModuleRows;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The project model Gradle evaluated ({@link GradleModel}) as a jk workspace: the root manifest
 * names every compiled project as a module, each module's manifest carries the dependencies its
 * configurations declare — a sibling as a workspace edge — and every Gradle construct jk has no
 * shape for (a script-registered task, a custom configuration, a plugin nothing maps) is a row
 * naming it at the module that has it.
 */
final class GradleModelImporter {

    /** The plugin ids every project is asked about, beside the installed plugins' import rules. */
    private static final List<String> KNOWN_PLUGINS = List.of(
            "java",
            "java-library",
            "application",
            "war",
            "groovy",
            "scala",
            "java-platform",
            "java-test-fixtures",
            "eclipse",
            "idea",
            "maven-publish",
            "jacoco",
            "checkstyle",
            "pmd",
            "org.jetbrains.kotlin.jvm",
            "org.jetbrains.kotlin.multiplatform",
            "org.jetbrains.kotlin.kapt",
            "org.jetbrains.kotlin.plugin.spring",
            "org.jetbrains.kotlin.plugin.jpa",
            "org.jetbrains.kotlin.plugin.serialization",
            "com.google.devtools.ksp",
            GradleImporter.DOKKA_PLUGIN,
            GradleImporter.GIT_PROPERTIES_PLUGIN);

    /** Plugin ids whose effect needs no row: implicit in jk, or absorbed by another mapping. */
    private static final Set<String> SILENT_PLUGINS = Set.of(
            "java",
            "java-library",
            "application",
            "maven-publish",
            "jacoco",
            "eclipse",
            "idea",
            "org.jetbrains.kotlin.jvm",
            "org.jetbrains.kotlin.plugin.spring",
            "org.jetbrains.kotlin.plugin.jpa",
            "org.jetbrains.kotlin.plugin.serialization",
            "org.jetbrains.kotlin.kapt",
            "com.google.devtools.ksp",
            GradleImporter.DOKKA_PLUGIN,
            GradleImporter.GIT_PROPERTIES_PLUGIN);

    /** Plugin class-name prefixes the probed ids already account for. */
    private static final List<String> MAPPED_PLUGIN_CLASSES = List.of(
            "org.jetbrains.kotlin.gradle",
            "org.springframework.boot.gradle",
            "io.spring.gradle.dependencymanagement",
            "org.jetbrains.dokka",
            "com.google.devtools.ksp",
            "com.gorylenko",
            "io.quarkus",
            "com.diffplug.gradle.spotless");

    /** Configurations Gradle and its plugins declare for their own tooling, not the build's dependencies. */
    private static final List<String> TOOL_CONFIGURATION_PREFIXES = List.of(
            "kotlin",
            "dokka",
            "ksp",
            "kapt",
            "spotless",
            "checkstyle",
            "pmd",
            "jacoco",
            "errorprone",
            "detekt",
            "ktlint",
            "animalsniffer",
            "signatures",
            "japicmp",
            "bootArchives",
            "default",
            "archives");

    private final GradleModel model;
    private final Path buildRoot;
    private final ImportReport.Builder report = ImportReport.builder();
    private final ModuleRows rows = new ModuleRows();
    private final Map<String, GradleImporter.PluginImportRule> importRules = GradleImporter.pluginImportRules();
    /** Gradle project path → the jk module name the sibling edge uses. */
    private final Map<String, String> namesByPath = new LinkedHashMap<>();

    private final Map<String, RepositorySpec> repositories = new LinkedHashMap<>();
    /** Whether the {@code mavenLocal()} row was written; one row however many projects declare it. */
    private boolean mavenLocal;

    private GradleModelImporter(GradleModel model, Path buildRoot) {
        this.model = model;
        this.buildRoot = buildRoot;
    }

    /** The plugin ids the model query asks each project about. */
    static List<String> probedPluginIds() {
        Set<String> ids = new LinkedHashSet<>(KNOWN_PLUGINS);
        ids.addAll(GradleImporter.pluginImportRules().keySet());
        return List.copyOf(ids);
    }

    /** Import the JSON the init script wrote for the build rooted at {@code buildRoot}. */
    static GradleBuildImport.Result importModel(String json, Path buildRoot) {
        return new GradleModelImporter(GradleModel.parse(json), buildRoot).run();
    }

    private GradleBuildImport.Result run() {
        GradleModel.Project root = model.projects().stream()
                .filter(GradleModel.Project::isRoot)
                .findFirst()
                .orElseGet(() -> model.projects().getFirst());
        List<GradleModel.Project> members = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<GradleModel.Project> byPath = new ArrayList<>(model.projects());
        byPath.sort(Comparator.comparing(GradleModel.Project::path));
        for (GradleModel.Project p : byPath) {
            if (p.isRoot()) continue;
            if (p.applies("java-platform")) {
                report.warning("`" + p.path() + "` (" + p.dir() + ") applies `java-platform`: a BOM, not a module;"
                        + " it is not a workspace module. Publish it with `jk export bom` if the build needs one.");
            } else if (!p.hasJvmSources() && p.configurations().isEmpty()) {
                skipped.add(p.path());
            } else {
                members.add(p);
            }
        }
        if (!skipped.isEmpty()) {
            report.warning("`" + String.join("`, `", skipped) + "` appl" + (skipped.size() == 1 ? "ies" : "y")
                    + " no JVM plugin and declare" + (skipped.size() == 1 ? "s" : "")
                    + " nothing; not workspace modules.");
        }
        for (String url : model.settingsRepositories()) repository(url);
        if (members.isEmpty()) {
            for (String url : root.repositories()) repository(url);
            JkBuild single = memberBuild(root, root.name(), "", new ArrayList<>(repositories.values()));
            return new GradleBuildImport.Result(single, Map.of(), finish());
        }
        nameMembers(members);
        Map<String, JkBuild> modules = new LinkedHashMap<>();
        for (GradleModel.Project p : members) {
            String name = Objects.requireNonNull(namesByPath.get(p.path()), p.path());
            modules.put(p.dir(), memberBuild(p, name, p.dir(), List.of()));
        }
        ImportReport.Builder rootRows = ImportReport.builder();
        if (root.hasJvmSources() || declaresDependencies(root)) {
            rootRows.warning("the root project `" + root.name() + "` has sources or dependencies of its own; a jk"
                    + " workspace root is a coordination point, so move them into a module.");
        }
        reportPlugins(root, new LinkedHashSet<>(root.plugins()), rootRows);
        reportTasks(root, rootRows);
        for (String url : root.repositories()) repository(url);
        rows.addAll("", rootRows.build());
        JkBuild rootBuild = JkBuild.builder(rootProject(root, members))
                .workspace(new Workspace(
                        members.stream().map(GradleModel.Project::dir).toList()))
                .repositories(new ArrayList<>(repositories.values()))
                .build();
        return new GradleBuildImport.Result(rootBuild, modules, finish());
    }

    private ImportReport finish() {
        rows.flush(report);
        return report.build();
    }

    /**
     * The jk name of each member: its Gradle project name, unless two members share one, in which
     * case each is named by its root-relative path with {@code /} as {@code -}, and a row says so.
     */
    private void nameMembers(List<GradleModel.Project> members) {
        Map<String, List<GradleModel.Project>> byName = new TreeMap<>();
        for (GradleModel.Project p : members)
            byName.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p);
        for (Map.Entry<String, List<GradleModel.Project>> e : byName.entrySet()) {
            List<GradleModel.Project> carriers = e.getValue();
            if (carriers.size() == 1) {
                namesByPath.put(carriers.getFirst().path(), e.getKey());
                continue;
            }
            List<String> renamed = new ArrayList<>();
            for (GradleModel.Project p : carriers) {
                String name = p.dir().replace('/', '-');
                namesByPath.put(p.path(), name);
                renamed.add(p.dir() + " → `" + name + "`");
            }
            report.warning("`"
                    + String.join(
                            "`, `",
                            carriers.stream().map(GradleModel.Project::dir).toList())
                    + "` all carry the name `" + e.getKey() + "`; each module is named by its path instead ("
                    + String.join(", ", renamed) + ").");
        }
    }

    /** The root's coordinates, filled from the members where Gradle left the root's blank or {@code unspecified}. */
    private Project rootProject(GradleModel.Project root, List<GradleModel.Project> members) {
        String group =
                root.group().isBlank() ? mostCommon(members, GradleModel.Project::group, "com.example") : root.group();
        String version = versionOf(root.version());
        if (version == null) version = mostCommon(members, p -> versionOf(p.version()), "0.1.0");
        Project.Builder builder =
                Project.builder(group, model.rootName().isBlank() ? root.name() : model.rootName(), version);
        int toolchain = 0;
        int java = 0;
        for (GradleModel.Project p : members) {
            toolchain = Math.max(toolchain, toolchainMajor(p));
            java = Math.max(java, javaRelease(p));
        }
        if (toolchain > 0) builder.jdkMajor(toolchain);
        if (java > 0) builder.java(java);
        return builder.description(root.description()).build();
    }

    private static @Nullable String versionOf(String gradleVersion) {
        return gradleVersion.isBlank() || gradleVersion.equals("unspecified") ? null : gradleVersion;
    }

    private static String mostCommon(
            List<GradleModel.Project> members, Function<GradleModel.Project, @Nullable String> field, String fallback) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GradleModel.Project p : members) {
            String v = field.apply(p);
            if (v != null && !v.isBlank()) counts.merge(v, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(fallback);
    }

    // --- one module ---------------------------------------------------------

    private JkBuild memberBuild(GradleModel.Project p, String name, String modulePath, List<RepositorySpec> repos) {
        Set<String> applied = new LinkedHashSet<>(p.plugins());
        ImportReport.Builder local = ImportReport.builder();
        Map<Scope, List<Dependency>> deps = new EnumMap<>(Scope.class);
        Set<String> hoisted = new HashSet<>();
        for (GradleModel.Configuration c : p.configurations()) mapConfiguration(p, c, deps, local, hoisted);
        bomPlatform(p, applied, deps);
        for (String url : p.repositories()) repository(url);
        List<PluginConfig> plugins = GradleImporter.mapPluginTables(applied, p.pluginVersions(), importRules, local);
        reportPlugins(p, applied, local);
        reportTasks(p, local);
        reportSourceSets(p, local);

        int toolchain = toolchainMajor(p);
        int java = javaRelease(p);
        String kotlin = p.kotlinVersion();
        Project.Builder project = Project.builder(
                        p.group().isBlank() ? "com.example" : p.group(),
                        name,
                        versionOf(p.version()) == null ? "0.1.0" : p.version())
                .kotlin(kotlin == null ? null : VersionSelector.parse(kotlin))
                .description(p.description());
        // A toolchain is a JDK the build asks for; source, target and --release are language levels
        // the host JDK compiles to, so only the former is a `jdk` pin.
        if (toolchain > 0) project.jdkMajor(toolchain);
        if (kotlin == null && java > 0) project.java(java);
        Map<String, String> manifest = new LinkedHashMap<>(p.manifest());
        manifest.remove("Manifest-Version");
        String mainClass = p.mainClass() != null ? p.mainClass() : manifest.remove("Main-Class");
        manifest.remove("Main-Class");
        BuildBlock build = BuildBlock.EMPTY;
        if (p.bootBuildInfo() || applied.contains(GradleImporter.GIT_PROPERTIES_PLUGIN)) {
            build = build.withBuildInfo(BuildBlock.BuildInfo.DEFAULT);
        }
        String dokka = p.pluginVersions().get(GradleImporter.DOKKA_PLUGIN);
        if (applied.contains(GradleImporter.DOKKA_PLUGIN) && dokka != null) {
            build = build.withDokka(
                    new BuildBlock.Dokka(VersionSelector.parse(dokka), BuildBlock.Dokka.Format.JAVADOC));
        }
        JkBuild.Builder builder = JkBuild.builder(project.build())
                .dependencies(new JkBuild.Dependencies(deps))
                .repositories(repos)
                .application(mainClass == null ? null : new JkBuild.Application(mainClass, false))
                .build(build);
        for (PluginConfig config : plugins) builder.pluginConfig(config);
        JkBuild jkBuild = builder.build();
        if (!manifest.isEmpty()) jkBuild = jkBuild.withManifest(manifest);
        rows.addAll(modulePath, local.build());
        return jkBuild;
    }

    /** The JDK the project's {@code java.toolchain} asks for; {@code 0} when it asks for none. */
    private static int toolchainMajor(GradleModel.Project p) {
        GradleModel.Java java = p.java();
        return java == null ? 0 : java.toolchain();
    }

    /** The language level the project compiles to: {@code --release}, else source, else target, else its toolchain. */
    private static int javaRelease(GradleModel.Project p) {
        GradleModel.Java java = p.java();
        if (java == null) return 0;
        if (java.release() > 0) return java.release();
        if (java.source() > 0) return java.source();
        if (java.target() > 0) return java.target();
        return java.toolchain();
    }

    /**
     * A project under the dependency-management plugin but without the Boot plugin takes Boot's BOM as
     * its {@code [platform-dependencies]} entry, so its version-less starters resolve; with the Boot
     * plugin applied the {@code [spring-boot]} table brings the BOM itself.
     */
    private static void bomPlatform(GradleModel.Project p, Set<String> applied, Map<Scope, List<Dependency>> deps) {
        String bom = p.springBootBom();
        if (bom == null || applied.contains("org.springframework.boot")) return;
        String[] parts = bom.split(":");
        if (parts.length != 3) return;
        List<Dependency> platforms = deps.computeIfAbsent(Scope.PLATFORM, s -> new ArrayList<>());
        String module = parts[0] + ":" + parts[1];
        if (platforms.stream().anyMatch(d -> d.module().equals(module))) return;
        platforms.add(Dependency.of(parts[1], module, VersionSelector.parse(parts[2])));
    }

    // --- configurations -----------------------------------------------------

    private void mapConfiguration(
            GradleModel.Project p,
            GradleModel.Configuration c,
            Map<Scope, List<Dependency>> deps,
            ImportReport.Builder local,
            Set<String> pinnedByManagement) {
        Scope scope = GradleDependencies.mapConfiguration(c.name());
        if (scope == null) {
            if (!isToolConfiguration(c.name()) && !c.dependencies().isEmpty()) {
                List<String> coords = c.dependencies().stream()
                        .map(d -> d.kind().equals("module")
                                ? d.module()
                                : d.kind().equals("project") ? "project(" + d.projectPath() + ")" : d.kind())
                        .toList();
                local.warning("configuration `" + c.name() + "` (" + coords.size()
                        + (coords.size() == 1 ? " dependency: " : " dependencies: ") + String.join(", ", coords)
                        + ") has no jk table; declare them under the scope they belong to.");
            }
            return;
        }
        for (GradleModel.Dependency d : c.dependencies())
            mapDependency(p, c.name(), scope, d, deps, local, pinnedByManagement);
        for (GradleModel.Constraint k : c.constraints()) {
            if (k.version().isBlank() || k.module().indexOf(':') <= 0) continue;
            String artifact = k.module().substring(k.module().indexOf(':') + 1);
            deps.computeIfAbsent(Scope.MANAGED, s -> new ArrayList<>())
                    .add(Dependency.of(
                            GradleDependencies.shortNameFor(k.module()).orElse(artifact),
                            k.module(),
                            VersionSelector.parse(k.version())));
        }
        if (scope == Scope.TEST
                && c.name().equals("testCompileOnly")
                && !c.dependencies().isEmpty()) {
            local.warning("`testCompileOnly` dependencies "
                    + String.join(
                            ", ",
                            c.dependencies().stream()
                                    .map(GradleModel.Dependency::module)
                                    .toList())
                    + " are written to [test-dependencies]: jk has no test-provided table, so they are on the"
                    + " test runtime classpath too.");
        }
    }

    /** Whether {@code p} declares a dependency on a configuration jk maps — tool configurations do not count. */
    private static boolean declaresDependencies(GradleModel.Project p) {
        for (GradleModel.Configuration c : p.configurations()) {
            if (GradleDependencies.mapConfiguration(c.name()) != null
                    && !c.dependencies().isEmpty()) return true;
        }
        return false;
    }

    private static boolean isToolConfiguration(String name) {
        if (name.endsWith("Classpath") || name.endsWith("Elements") || name.contains("~")) return true;
        for (String prefix : TOOL_CONFIGURATION_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private void mapDependency(
            GradleModel.Project p,
            String configuration,
            Scope scope,
            GradleModel.Dependency d,
            Map<Scope, List<Dependency>> deps,
            ImportReport.Builder local,
            Set<String> pinnedByManagement) {
        switch (d.kind()) {
            case "project" -> {
                String sibling = d.projectPath() == null ? null : namesByPath.get(d.projectPath());
                if (sibling == null) {
                    local.warning("dependency on project `" + d.projectPath() + "` (configuration `" + configuration
                            + "`) names a project that is not a workspace module; dropped.");
                    return;
                }
                Dependency edge = Dependency.workspace(sibling);
                if (!d.excludes().isEmpty()) edge = edge.withExclusions(exclusions(d));
                deps.computeIfAbsent(scope, s -> new ArrayList<>()).add(edge);
            }
            case "module" -> mapModule(p, scope, d, deps, local, pinnedByManagement);
            case "files" ->
                local.warning("file dependency `" + String.join(", ", d.files()) + "` on configuration `"
                        + configuration + "` has no coordinate to import; dropped.");
            default ->
                local.warning("dependency `" + (d.text() == null ? d.kind() : d.text()) + "` on configuration `"
                        + configuration + "` is not a coordinate or a project; dropped.");
        }
    }

    private void mapModule(
            GradleModel.Project p,
            Scope scope,
            GradleModel.Dependency d,
            Map<Scope, List<Dependency>> deps,
            ImportReport.Builder local,
            Set<String> pinnedByManagement) {
        if (d.group().isBlank() || d.artifact().isBlank()) {
            local.error("dependency `" + d.module() + "` has no group or no artifact; dropped.");
            return;
        }
        Scope target = d.isPlatform() ? Scope.PLATFORM : scope;
        String module = d.module();
        String shortName = GradleDependencies.shortNameFor(module).orElse(d.artifact());
        String version = d.effectiveVersion();
        Dependency dep;
        if (version.isBlank()) {
            String managed = p.springBootBom() == null ? p.managedVersions().get(module) : null;
            if (managed != null && !managed.isBlank()) {
                dep = Dependency.of(shortName, module, VersionSelector.parse(managed));
                pinnedByManagement.add(module);
                local.warning("version of " + module + " (" + managed + ") comes from the dependency-management"
                        + " plugin's imported BOM; written as an exact pin.");
            } else {
                dep = Dependency.platformManaged(shortName, module);
            }
        } else if (version.equals(GradleDependencies.REFRESH_VERSIONS_PLACEHOLDER)) {
            local.warning("dependency `" + module + "` has the version `_` (refreshVersions keeps the pin in"
                    + " versions.properties); written without a version — pin it in jk.toml.");
            dep = Dependency.platformManaged(shortName, module);
        } else if (version.endsWith("+") || version.startsWith("latest.")) {
            local.warning("dependency `" + module + "` has the dynamic version `" + version
                    + "`; written as `latest`, which the first `jk lock` pins.");
            dep = Dependency.of(shortName, module, VersionSelector.parse("latest"));
        } else {
            dep = Dependency.of(shortName, module, VersionSelector.parse(version));
        }
        if (d.classifier() != null && !d.classifier().isBlank()) dep = dep.withClassifier(d.classifier());
        if (d.type() != null && !d.type().isBlank()) {
            local.warning("artifact type `" + d.type() + "` on `" + module + "` dropped; jk resolves the jar.");
        }
        List<String> exclusions = exclusions(d);
        if (!exclusions.isEmpty()) dep = dep.withExclusions(exclusions);
        deps.computeIfAbsent(target, s -> new ArrayList<>()).add(dep);
    }

    private static List<String> exclusions(GradleModel.Dependency d) {
        List<String> out = new ArrayList<>(d.excludes());
        if (d.intransitive() && !out.contains("*:*")) out.add("*:*");
        return out;
    }

    // --- rows ---------------------------------------------------------------

    private static void reportPlugins(GradleModel.Project p, Set<String> applied, ImportReport.Builder local) {
        for (String id : applied) {
            if (SILENT_PLUGINS.contains(id)
                    || GradleImporter.pluginImportRules().containsKey(id)) continue;
            switch (id) {
                case "war" ->
                    local.warning("plugin `war` is applied: jk builds a jar; a war packaging is not imported.");
                case "groovy", "scala" ->
                    local.warning("plugin `" + id + "` is applied: set `project." + id
                            + "` to the compiler version the build uses.");
                case "java-test-fixtures" ->
                    local.warning(
                            "plugin `java-test-fixtures` is applied: declare `[test] fixtures = true` and move the fixtures"
                                    + " under src/fixtures/java.");
                case "checkstyle", "pmd" ->
                    local.warning("plugin `" + id + "` is applied: configure the [lint] table by hand.");
                default ->
                    local.warning(
                            "Gradle plugin `" + id + "` is not mapped; what it configures is not in this import.");
            }
        }
        for (String cls : p.pluginClasses()) {
            if (MAPPED_PLUGIN_CLASSES.stream().anyMatch(cls::startsWith)) continue;
            local.warning("Gradle plugin `" + cls + "` is not mapped; what it configures is not in this import.");
        }
    }

    private static void reportTasks(GradleModel.Project p, ImportReport.Builder local) {
        for (GradleModel.Task t : p.tasks()) {
            String type = t.type().substring(t.type().lastIndexOf('.') + 1);
            local.warning("task `" + t.name() + "` (" + type + (t.group().isBlank() ? "" : ", group " + t.group())
                    + ") is registered by the build script and is not translated; write it as a jk build script"
                    + " under .jk/ if the build needs it.");
        }
    }

    private static void reportSourceSets(GradleModel.Project p, ImportReport.Builder local) {
        for (GradleModel.SourceSet s : p.sourceSets()) {
            if (!s.name().equals("main") && !s.name().equals("test")) {
                local.warning(
                        "source set `" + s.name() + "` has no jk equivalent; its sources are not in this import.");
                continue;
            }
            List<String> odd = new ArrayList<>();
            String prefix = "src/" + s.name() + "/";
            for (String dir : s.java()) if (!dir.equals(prefix + "java")) odd.add(dir);
            for (String dir : s.kotlin())
                if (!dir.equals(prefix + "kotlin") && !dir.equals(prefix + "java")) odd.add(dir);
            for (String dir : s.resources()) if (!dir.equals(prefix + "resources")) odd.add(dir);
            if (!odd.isEmpty()) {
                local.warning("source set `" + s.name() + "` reads from " + String.join(", ", odd)
                        + ", outside jk's layout; move the sources or generate them with a build script.");
            }
        }
    }

    private void repository(String url) {
        if (url.isBlank() || GradleImporter.isCentral(url)) return;
        if (url.startsWith("file:") && url.replace('\\', '/').matches(".*/\\.m2/repository/?")) {
            if (mavenLocal) return;
            mavenLocal = true;
            report.warning("`mavenLocal()` recognised but not mapped — jk reads ~/.m2 by default.");
            return;
        }
        String normalized = url.endsWith("/") ? url : url + "/";
        if (repositories.containsKey(normalized)) return;
        try {
            String name = "repo" + (repositories.size() + 1);
            repositories.put(normalized, new RepositorySpec(name, RepositorySpec.normalizedUrl(new URI(normalized))));
        } catch (URISyntaxException e) {
            report.warning("repository URL `" + url + "` is not a valid URI; skipped.");
        }
    }
}
