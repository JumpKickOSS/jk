// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.ImportedKotlin;
import cc.jumpkick.compat.NodeImportMapping;
import cc.jumpkick.compat.RelocationRules;
import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.groovy.GroovyResolver;
import cc.jumpkick.layout.Languages;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.PomMetadata;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.TestJvm;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.mvn.ModuleRows;
import cc.jumpkick.mvn.NullnessImport;
import cc.jumpkick.version.Versions;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /** The xjc schema-compiler plugin, whose extension is the {@code [jaxb]} table. */
    static final String XJC_PLUGIN = "com.github.bjornvester.xjc";

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
            GradleImporter.GIT_PROPERTIES_PLUGIN,
            "com.gradleup.shadow",
            "com.github.johnrengelman.shadow",
            "io.github.goooler.shadow",
            XJC_PLUGIN);

    /** Plugin ids whose effect needs no row: implicit in jk, or absorbed by another mapping. */
    private static final Set<String> SILENT_PLUGINS = Set.of(
            GradleNodeImport.PLUGIN,
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
            "com.gradleup.shadow",
            "com.github.johnrengelman.shadow",
            "io.github.goooler.shadow",
            "com.google.devtools.ksp",
            GradleImporter.DOKKA_PLUGIN,
            GradleImporter.GIT_PROPERTIES_PLUGIN,
            XJC_PLUGIN);

    /** Plugin class-name prefixes the probed ids already account for. */
    private static final List<String> MAPPED_PLUGIN_CLASSES = List.of(
            "org.jetbrains.kotlin.gradle",
            "org.springframework.boot.gradle",
            "io.spring.gradle.dependencymanagement",
            "org.jetbrains.dokka",
            "com.google.devtools.ksp",
            "com.gorylenko",
            "io.quarkus",
            "com.diffplug.gradle.spotless",
            "com.github.jengelman.gradle.plugins.shadow",
            "com.github.bjornvester.xjc");

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
            "xjc",
            "bootArchives",
            "default",
            "archives");

    /** The groups whose {@code groovy} artifact is the Groovy compiler a Gradle {@code groovy} plugin runs. */
    private static final List<String> GROOVY_GROUPS = List.of("org.apache.groovy", "org.codehaus.groovy");

    private final GradleModel model;
    private final Path buildRoot;
    private final RefreshVersions refreshVersions;
    private final CatalogShortNames shortNames = CatalogShortNames.layered();
    private final ImportReport.Builder report = ImportReport.builder();
    private final ModuleRows rows = new ModuleRows();
    private final Map<String, GradleImporter.PluginImportRule> importRules = GradleImporter.pluginImportRules();
    /** Gradle project path → the jk module name the sibling edge uses. */
    private final Map<String, String> namesByPath = new LinkedHashMap<>();

    private final Map<String, RepositorySpec> repositories = new LinkedHashMap<>();
    /** The {@code ~/.jk/config.toml} tables that carry the credentials the build reads from the environment. */
    private final List<UserConfigEditor.RepositoryTable> machineRepositories = new ArrayList<>();
    /** The workspace root's {@code java} and {@code jdk}, which a member inherits rather than repeats; 0 for none. */
    private int rootJava;

    private int rootJdk;

    /** Whether the {@code mavenLocal()} row was written; one row however many projects declare it. */
    private boolean mavenLocal;

    private GradleModelImporter(GradleModel model, Path buildRoot, RefreshVersions refreshVersions) {
        this.model = model;
        this.buildRoot = buildRoot;
        this.refreshVersions = refreshVersions;
    }

    /** The plugin ids the model query asks each project about. */
    static List<String> probedPluginIds() {
        Set<String> ids = new LinkedHashSet<>(KNOWN_PLUGINS);
        ids.addAll(GradleImporter.pluginImportRules().keySet());
        return List.copyOf(ids);
    }

    /**
     * Import the JSON the init script wrote for the build rooted at {@code buildRoot}; {@code
     * refreshVersions} is the build's {@code versions.properties}, which a {@code _} version reads.
     */
    static GradleBuildImport.Result importModel(String json, Path buildRoot, RefreshVersions refreshVersions) {
        return new GradleModelImporter(GradleModel.parse(json), buildRoot, refreshVersions).run();
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
        for (GradleModel.Repository r : model.settingsRepositories()) repository(r);
        if (members.isEmpty()) {
            for (GradleModel.Repository r : root.repositories()) repository(r);
            JkBuild single = memberBuild(root, artifactName(root, report), "", new ArrayList<>(repositories.values()));
            return new GradleBuildImport.Result(single, Map.of(), finish(), machineRepositories);
        }
        nameMembers(members);
        Project rootProject = rootProject(root, members);
        rootJava = rootProject.java();
        rootJdk = rootProject.jdkMajor();
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
        for (GradleModel.Repository r : root.repositories()) repository(r);
        rows.addAll("", rootRows.build());
        JkBuild rootBuild = JkBuild.builder(rootProject)
                .workspace(new Workspace(
                        members.stream().map(GradleModel.Project::dir).toList()))
                .repositories(new ArrayList<>(repositories.values()))
                .build();
        return new GradleBuildImport.Result(rootBuild, modules, finish(), machineRepositories);
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
            byName.computeIfAbsent(artifactName(p, report), k -> new ArrayList<>())
                    .add(p);
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

    /**
     * The artifact id the build publishes {@code p} under: its one {@code MavenPublication}'s
     * {@code artifactId}, else {@code base.archivesName}, else the project name. Several
     * publications are a row, and the project keeps its archives name.
     */
    private static String artifactName(GradleModel.Project p, ImportReport.Builder report) {
        List<String> ids = p.publications().stream()
                .map(GradleModel.Publication::artifactId)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (ids.size() == 1) return ids.getFirst();
        if (ids.size() > 1) {
            report.warning("`" + p.path() + "` publishes " + ids.size() + " artifacts (" + String.join(", ", ids)
                    + "); a jk module publishes one, so it is named after its archives and the rest are not written.");
        }
        String archives = p.archivesName();
        return archives == null || archives.isBlank() ? p.name() : archives;
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
        boolean platformed = bomPlatforms(p, applied, deps, local);
        for (GradleModel.Configuration c : p.configurations()) mapConfiguration(p, c, deps, local, platformed);
        for (GradleModel.Repository r : p.repositories()) repository(r);
        List<PluginConfig> plugins =
                new ArrayList<>(GradleImporter.mapPluginTables(applied, p.pluginVersions(), importRules, local));
        GradleModel.Xjc xjc = p.xjc();
        if (xjc != null) plugins.add(jaxbTable(xjc, local));
        String groovy = applied.contains("groovy") ? groovyVersion(p, local) : null;
        Set<String> unreported = new LinkedHashSet<>(applied);
        unreported.remove("groovy");
        reportPlugins(p, unreported, local);
        reportTasks(p, local);
        reportSourceSets(p, local);

        int toolchain = toolchainMajor(p);
        int java = javaRelease(p);
        String kotlin = p.kotlinVersion();
        Project.Builder project = Project.builder(
                        p.group().isBlank() ? "com.example" : p.group(),
                        name,
                        versionOf(p.version()) == null ? "0.1.0" : p.version())
                .kotlin(kotlin == null ? null : ImportedKotlin.floored(VersionSelector.parse(kotlin), local))
                .groovy(groovy == null ? null : VersionSelector.parse(groovy))
                .description(p.description() != null ? p.description() : pomDescription(p));
        // Only a toolchain the host JDK cannot stand in for is a `jdk` pin (see toolchainMajor). A
        // Kotlin or Groovy module compiles Java only when `java` is declared, as Gradle compiles its
        // src/*/java: written when the module has Java sources, at the level of the JDK compiling
        // when Gradle names none. A member does not repeat the level or the JDK its root declares.
        if (toolchain > 0 && toolchain != rootJdk) project.jdkMajor(toolchain);
        int level = kotlin == null && groovy == null
                ? java
                : hasJavaSources(p) ? (java > 0 ? java : Runtime.version().feature()) : 0;
        if (level > 0 && level != rootJava) project.java(level);
        Map<String, String> manifest = new LinkedHashMap<>(p.manifest());
        manifest.remove("Manifest-Version");
        String mainClass = p.mainClass() != null ? p.mainClass() : manifest.remove("Main-Class");
        manifest.remove("Main-Class");
        BuildBlock build = BuildBlock.EMPTY;
        if (applied.contains("war")) build = build.withWar(new BuildBlock.War(null, BuildBlock.War.DEFAULT_WEBAPP));
        if (p.bootBuildInfo() || applied.contains(GradleImporter.GIT_PROPERTIES_PLUGIN)) {
            build = build.withBuildInfo(BuildBlock.BuildInfo.DEFAULT);
        }
        String dokka = p.pluginVersions().get(GradleImporter.DOKKA_PLUGIN);
        if (applied.contains(GradleImporter.DOKKA_PLUGIN) && dokka != null) {
            build = build.withDokka(
                    new BuildBlock.Dokka(VersionSelector.parse(dokka), BuildBlock.Dokka.Format.JAVADOC));
        }
        // Shadow is the fat jar — the application's with a main, the library's without — and the
        // shadowJar task's relocators are its rules where the rules agree with them.
        boolean shadow = applied.stream().anyMatch(GradleImporter.SHADOW_PLUGINS::contains)
                || !p.relocations().isEmpty();
        RelocationRules.Mapped relocations = RelocationRules.map(p.relocations());
        if (!relocations.unmapped().isEmpty()) {
            local.warning("Shadow `relocate` " + String.join(", ", relocations.unmapped())
                    + " — `relocate` moves whole packages, first rule winning; what the rules do not express is not"
                    + " written, and those classes are bundled under their own names.");
        }
        JkBuild.Builder builder = JkBuild.builder(project.build())
                .dependencies(new JkBuild.Dependencies(deps))
                .repositories(repos)
                .application(
                        mainClass == null
                                ? null
                                : new JkBuild.Application(mainClass, shadow, false, false, null, relocations.rules()))
                .library(mainClass == null && shadow ? new JkBuild.Library(true, relocations.rules()) : null)
                .build(build);
        for (PluginConfig config : plugins) builder.pluginConfig(config);
        JkBuild jkBuild = builder.build();
        if (!manifest.isEmpty()) jkBuild = jkBuild.withManifest(manifest);
        jkBuild = compilerArgs(p, jkBuild, local);
        jkBuild = testJvm(p, jkBuild);
        jkBuild = publication(p, jkBuild);
        Path projectDir = buildRoot.resolve(p.dir());
        NodeImportMapping.Mapped node = GradleNodeImport.map(projectDir, applied, local);
        if (node == null) node = NodeImportMapping.quinoa(projectDir, local);
        jkBuild = NodeImportMapping.apply(jkBuild, node);
        rows.addAll(modulePath, local.build());
        return jkBuild;
    }

    /**
     * {@code compileJava}'s javac arguments as {@code [javac] args}, with a {@code [javac.test]}
     * table when {@code compileTestJava}'s differ; arguments jk sets itself are left out. An Error
     * Prone plugin argument brings the {@code errorprone} configuration onto the processor path, and
     * NullAway run alone through it becomes {@code [nullness]}, as a POM's does.
     */
    private static JkBuild compilerArgs(GradleModel.Project p, JkBuild build, ImportReport.Builder local) {
        List<String> main = p.mainCompilerArgs() == null ? List.of() : ownedByJk(p.mainCompilerArgs());
        List<String> test = p.testCompilerArgs() == null ? main : ownedByJk(p.testCompilerArgs());
        if (main.isEmpty() && test.isEmpty()) return build;
        JavacConfig javac = main.equals(test)
                ? new JavacConfig(Map.of(), main)
                : new JavacConfig(Map.of(), main, new JavacConfig(Map.of(), test));
        build = build.withBuild(build.build().withJavac(javac));
        boolean errorProne = main.stream().anyMatch(a -> a.startsWith(ERROR_PRONE_PLUGIN))
                || test.stream().anyMatch(a -> a.startsWith(ERROR_PRONE_PLUGIN));
        if (!errorProne) return build;
        List<Dependency> tools = new ArrayList<>();
        for (GradleModel.Configuration c : p.configurations()) {
            if (!c.name().equals("errorprone")) continue;
            for (GradleModel.Dependency d : c.dependencies()) {
                if (!d.kind().equals("module") || d.effectiveVersion().isBlank()) continue;
                tools.add(Dependency.of(d.artifact(), d.module(), VersionSelector.parse(d.effectiveVersion())));
            }
        }
        if (!tools.isEmpty()) {
            EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
            build.dependencies().byScope().forEach(byScope::put);
            List<Dependency> processor = new ArrayList<>(byScope.getOrDefault(Scope.PROCESSOR, List.of()));
            for (Dependency tool : tools) {
                if (processor.stream().noneMatch(d -> d.module().equals(tool.module()))) processor.add(tool);
            }
            byScope.put(Scope.PROCESSOR, processor);
            build = build.withDependencies(new JkBuild.Dependencies(byScope));
        }
        return NullnessImport.map(build, local);
    }

    private static final String ERROR_PRONE_PLUGIN = "-Xplugin:ErrorProne";

    /** Arguments that take the next one as their value, and that jk passes itself. */
    private static final Set<String> JK_OWNED_WITH_VALUE = Set.of(
            "--release",
            "-source",
            "--source",
            "-target",
            "--target",
            "-encoding",
            "-d",
            "-s",
            "-h",
            "-classpath",
            "-cp",
            "--class-path",
            "-processorpath",
            "--processor-path",
            "--module-path",
            "-p",
            "--processor-module-path",
            "-sourcepath",
            "--source-path");

    /** {@code args} without what jk sets itself: the level, the encoding, the paths and output directories. */
    private static List<String> ownedByJk(List<String> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (JK_OWNED_WITH_VALUE.contains(a)) {
                i++;
            } else if (!a.startsWith("--release=") && !a.startsWith("-Xlint:-options")) {
                out.add(a);
            }
        }
        return out;
    }

    /** The {@code test} task's JVM arguments and system properties as {@code [test] jvm-args} and {@code system-properties}. */
    private static JkBuild testJvm(GradleModel.Project p, JkBuild build) {
        GradleModel.TestTask test = p.test();
        if (test == null || test.jvmArgs().isEmpty() && test.systemProperties().isEmpty()) return build;
        Map<String, String> properties = new TreeMap<>(test.systemProperties());
        return build.withBuild(build.build().withTestJvm(new TestJvm(test.jvmArgs(), properties)));
    }

    /** The one publication's POM description; null with none or several. */
    private static @Nullable String pomDescription(GradleModel.Project p) {
        return p.publications().size() == 1 ? p.publications().getFirst().pomDescription() : null;
    }

    /** The one publication's POM name and url as {@code [publish]}. */
    private static JkBuild publication(GradleModel.Project p, JkBuild build) {
        if (p.publications().size() != 1) return build;
        GradleModel.Publication pub = p.publications().getFirst();
        if (pub.pomName() == null && pub.pomUrl() == null) return build;
        return build.withPublish(
                new PomMetadata(pub.pomName(), null, pub.pomUrl(), List.of(), List.of(), null, null, false, null));
    }

    /**
     * The JDK the project's {@code java.toolchain} pins; {@code 0} when it asks for none, or for a
     * major the host JDK compiles to with {@code --release} and names no vendor — a language level,
     * which {@code java} already says.
     */
    private static int toolchainMajor(GradleModel.Project p) {
        GradleModel.Java java = p.java();
        if (java == null || java.toolchain() == 0) return 0;
        if (java.vendor() == null && java.toolchain() <= Runtime.version().feature()) return 0;
        return java.toolchain();
    }

    /**
     * The language level the project compiles to: {@code --release}, else Kotlin's {@code
     * jvmTarget}, else source, else target, else its toolchain.
     */
    private static int javaRelease(GradleModel.Project p) {
        GradleModel.Java java = p.java();
        if (java == null) return p.kotlinJvmTarget();
        if (java.release() > 0) return java.release();
        if (p.kotlinJvmTarget() > 0) return p.kotlinJvmTarget();
        if (java.source() > 0) return java.source();
        if (java.target() > 0) return java.target();
        return java.toolchain();
    }

    /** Whether a {@code .java} file sits under the project's main or test Java roots, or anywhere under its src/. */
    private boolean hasJavaSources(GradleModel.Project p) {
        Path dir = buildRoot.resolve(p.dir());
        for (GradleModel.SourceSet s : p.sourceSets()) {
            for (String root : s.java()) {
                if (Languages.anySourceUnder(dir.resolve(root), ".java")) return true;
            }
        }
        return Languages.anySourceUnder(dir.resolve("src"), ".java");
    }

    /**
     * The Groovy a {@code groovy} plugin compiles with: the {@code groovy} artifact the classpaths
     * resolve, else one a dependency declares, else a declared Groovy BOM's version; failing all
     * three, jk's default with a row. A version below jk's floor is written as jk's default with a row.
     */
    private static String groovyVersion(GradleModel.Project p, ImportReport.Builder local) {
        String version = null;
        for (String group : GROOVY_GROUPS) {
            if (version == null) version = p.resolved().get(group + ":groovy");
        }
        for (GradleModel.Configuration c : p.configurations()) {
            for (GradleModel.Dependency d : c.dependencies()) {
                if (version != null) break;
                if (!d.kind().equals("module") || !GROOVY_GROUPS.contains(d.group())) continue;
                String declared = d.effectiveVersion();
                if (declared.isBlank()
                        || !(d.artifact().equals("groovy") || d.artifact().equals("groovy-bom"))) continue;
                version = declared;
            }
        }
        if (version == null) {
            local.warning("plugin `groovy` is applied and no Groovy version could be read from the build; `groovy = \""
                    + GroovyResolver.DEFAULT_VERSION
                    + "\"` (jk's default) is written — set the version the build uses.");
            return GroovyResolver.DEFAULT_VERSION;
        }
        if (GroovyResolver.belowFloor(version)) {
            local.warning(GroovyResolver.floorNote(version));
            return GroovyResolver.DEFAULT_VERSION;
        }
        return version;
    }

    /**
     * The xjc plugin's extension as the {@code [jaxb]} table: {@code xsdDir} is {@code src}, {@code
     * defaultPackage} is {@code package}, {@code bindingFiles} are {@code bindings} and {@code
     * options} are {@code arguments}.
     */
    private static PluginConfig jaxbTable(GradleModel.Xjc xjc, ImportReport.Builder local) {
        Map<String, Object> table = new LinkedHashMap<>();
        String src = xjc.xsdDir() == null ? "src/main/resources" : xjc.xsdDir();
        if (!src.equals(JAXB_DEFAULT_SRC)) table.put("src", src);
        if (xjc.defaultPackage() != null) table.put("package", xjc.defaultPackage());
        if (!xjc.bindingFiles().isEmpty()) table.put("bindings", xjc.bindingFiles());
        if (!xjc.options().isEmpty()) table.put("arguments", xjc.options());
        local.warning("plugin `" + XJC_PLUGIN + "` is `[jaxb]`: xjc generates the classes into the compile from the"
                + " schemas under `" + src + "`; the module keeps `jakarta.xml.bind:jakarta.xml.bind-api` for the"
                + " compile and a JAXB runtime (`org.glassfish.jaxb:jaxb-runtime`) for run time.");
        return new PluginConfig("jaxb", table);
    }

    /** The {@code [jaxb]} table's own default schema directory. */
    private static final String JAXB_DEFAULT_SRC = "src/main/xsd";

    /**
     * The BOMs the project's {@code dependencyManagement { imports { mavenBom … } } } block names are
     * its {@code [platform-dependencies]} entries, so the version-less dependencies they manage stay
     * platform-managed; a block naming none under a Boot plugin on the classpath imports Boot's BOM.
     * Boot's BOM is written only without the Boot plugin, whose {@code [spring-boot]} table brings it.
     * A BOM whose version reads a property nothing defines is a row. Answers whether a BOM was written.
     */
    private boolean bomPlatforms(
            GradleModel.Project p, Set<String> applied, Map<Scope, List<Dependency>> deps, ImportReport.Builder local) {
        List<String> boms = new ArrayList<>(p.importedBoms());
        if (boms.isEmpty() && p.springBootBom() != null) boms.add(p.springBootBom());
        boolean written = false;
        for (String bom : boms) {
            if (bom.indexOf('$') >= 0) {
                local.warning("dependencyManagement imports the BOM `" + bom + "`, whose version reads a property"
                        + " nothing defines; declare it under [platform-dependencies] by hand.");
                continue;
            }
            String[] parts = bom.split(":");
            if (parts.length != 3) {
                local.warning("dependencyManagement imports `" + bom + "`, which is not `group:artifact:version`;"
                        + " declare the BOM under [platform-dependencies] by hand.");
                continue;
            }
            if (bom.equals(p.springBootBom()) && applied.contains("org.springframework.boot")) {
                written = true;
                continue;
            }
            List<Dependency> platforms = deps.computeIfAbsent(Scope.PLATFORM, s -> new ArrayList<>());
            String module = parts[0] + ":" + parts[1];
            written = true;
            if (platforms.stream().anyMatch(d -> d.module().equals(module))) continue;
            platforms.add(
                    Dependency.of(shortNames.of(module).orElse(parts[1]), module, VersionSelector.parse(parts[2])));
        }
        return written;
    }

    // --- configurations -----------------------------------------------------

    private void mapConfiguration(
            GradleModel.Project p,
            GradleModel.Configuration c,
            Map<Scope, List<Dependency>> deps,
            ImportReport.Builder local,
            boolean platformed) {
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
        for (GradleModel.Dependency d : c.dependencies()) mapDependency(p, c.name(), scope, d, deps, local, platformed);
        for (GradleModel.Constraint k : c.constraints()) {
            if (k.version().isBlank() || k.module().indexOf(':') <= 0) continue;
            String artifact = k.module().substring(k.module().indexOf(':') + 1);
            deps.computeIfAbsent(Scope.MANAGED, s -> new ArrayList<>())
                    .add(Dependency.of(
                            shortNames.of(k.module()).orElse(artifact),
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
            boolean platformed) {
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
            case "module" -> mapModule(p, scope, d, deps, local, platformed);
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
            boolean platformed) {
        if (d.group().isBlank() || d.artifact().isBlank()) {
            local.error("dependency `" + d.module() + "` has no group or no artifact; dropped.");
            return;
        }
        Scope target = d.isPlatform() ? Scope.PLATFORM : scope;
        String module = d.module();
        // jk has one platform table for every scope: a BOM each configuration imports is one row.
        if (target == Scope.PLATFORM
                && deps.getOrDefault(Scope.PLATFORM, List.of()).stream()
                        .anyMatch(e -> e.module().equals(module))) return;
        String shortName = shortNames.of(module).orElse(d.artifact());
        String version = d.effectiveVersion();
        Dependency dep;
        if (version.isBlank()) {
            // A BOM written as a platform row manages the version; without one, the version the
            // dependency-management plugin supplies is all there is to write.
            String managed = platformed ? null : p.managedVersions().get(module);
            if (managed != null && !managed.isBlank()) {
                dep = Dependency.of(shortName, module, VersionSelector.parse(managed));
                local.warning("version of " + module + " (" + managed + ") comes from the dependency-management"
                        + " plugin's `dependencyManagement { }` block, not a BOM this import can name; written as"
                        + " an exact pin.");
            } else {
                dep = Dependency.platformManaged(shortName, module);
            }
        } else if (version.equals(GradleDependencies.REFRESH_VERSIONS_PLACEHOLDER)) {
            RefreshVersions.Lookup pin = refreshVersions.lookup(d.group(), d.artifact());
            if (pin.found()) {
                dep = Dependency.of(shortName, module, VersionSelector.parse(Objects.requireNonNull(pin.version())));
            } else {
                local.warning(GradleDependencies.placeholderRow(module, pin));
                dep = Dependency.platformManaged(shortName, module);
            }
        } else if (version.endsWith("+") || version.startsWith("latest.")) {
            local.warning("dependency `" + module + "` has the dynamic version `" + version
                    + "`; written as `latest`, which the first `jk lock` pins.");
            dep = Dependency.of(shortName, module, VersionSelector.parse("latest"));
        } else if (platformed && managedAbove(p, module, version)) {
            // Gradle's dependency-management lets a declared version undercut the BOM; a jk pin below
            // the version the platform aligns the rest of the graph on does not resolve.
            local.warning("`" + module + "` is declared at " + version + ", below the "
                    + p.managedVersions().get(module) + " its BOM manages; written as `managed` so it resolves"
                    + " with the rest of the platform.");
            dep = Dependency.platformManaged(shortName, module);
        } else {
            dep = Dependency.of(shortName, module, VersionSelector.parse(raised(p, d, version, local)));
        }
        if (d.classifier() != null && !d.classifier().isBlank()) dep = dep.withClassifier(d.classifier());
        if (d.type() != null && !d.type().isBlank()) {
            local.warning("artifact type `" + d.type() + "` on `" + module + "` dropped; jk resolves the jar.");
        }
        List<String> exclusions = exclusions(d);
        if (!exclusions.isEmpty()) dep = dep.withExclusions(exclusions);
        deps.computeIfAbsent(target, s -> new ArrayList<>()).add(dep);
    }

    /** Whether the dependency-management plugin manages {@code module} at a version above {@code version}. */
    private static boolean managedAbove(GradleModel.Project p, String module, String version) {
        String managed = p.managedVersions().get(module);
        return managed != null && !managed.isBlank() && Versions.compare(managed, version) > 0;
    }

    /**
     * The version to pin for a declared {@code version}: Gradle's resolved one when a dependency
     * of the graph asks for a higher one and Gradle's conflict resolution took it. A strict version
     * is what Gradle resolves already. jk pins what it writes, so a pin below what the graph needs
     * would not resolve.
     */
    private static String raised(
            GradleModel.Project p, GradleModel.Dependency d, String version, ImportReport.Builder local) {
        String resolved = p.resolved().get(d.module());
        if (resolved == null || d.strict() != null || Versions.compare(resolved, version) <= 0) return version;
        local.warning("`" + d.module() + "` is declared at " + version + " and Gradle resolves " + resolved
                + ", which a dependency of the graph asks for; written as " + resolved + ".");
        return resolved;
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

    /**
     * A repository the build declares, under its Gradle name: the groups its {@code content { }}
     * filter includes are its {@code groups}, and a credential the build reads from environment
     * variables becomes a {@code ~/.jk/config.toml} table naming them, since a project manifest may
     * read no variable but the repository's own {@code JK_REPO_<ID>_*}.
     */
    private void repository(GradleModel.Repository repo) {
        String url = repo.url();
        if (url.isBlank() || GradleImporter.isCentral(url)) return;
        if (url.startsWith("file:") && url.replace('\\', '/').matches(".*/\\.m2/repository/?")) {
            if (mavenLocal) return;
            mavenLocal = true;
            report.warning("`mavenLocal()` recognised but not mapped — jk reads ~/.m2 by default.");
            return;
        }
        String normalized = url.endsWith("/") ? url : url + "/";
        if (repositories.containsKey(normalized)) return;
        URI uri;
        try {
            uri = RepositorySpec.normalizedUrl(new URI(normalized));
        } catch (URISyntaxException e) {
            report.warning("repository URL `" + url + "` is not a valid URI; skipped.");
            return;
        }
        String name = repositoryName(repo.name());
        repositories.put(normalized, new RepositorySpec(name, uri, null, null, repo.groups()));
        if (!repo.unmappedContent().isEmpty()) {
            report.warning(
                    "repository `" + name + "` filters its content by " + String.join(", ", repo.unmappedContent())
                            + "; jk binds whole groups to a repository (`groups`), so these filters are not written.");
        }
        GradleModel.Credential credential = repo.credential();
        if (credential != null) credential(name, uri, repo.groups(), credential);
    }

    private void credential(String name, URI url, List<String> groups, GradleModel.Credential credential) {
        String secretEnv = credential.secretEnv();
        Map<String, String> keys = new LinkedHashMap<>();
        switch (credential.kind()) {
            case "header" -> {
                if (secretEnv != null) keys.put("token", "${" + secretEnv + "}");
            }
            case "password" -> {
                String username = credential.usernameEnv() != null
                        ? "${" + credential.usernameEnv() + "}"
                        : credential.username();
                if (secretEnv != null && username != null) {
                    keys.put("username", username);
                    keys.put("password", "${" + secretEnv + "}");
                }
            }
            default -> {}
        }
        String prefix = "JK_REPO_" + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_";
        String ci = " Where that table is not (CI), export " + prefix + "TOKEN (or " + prefix + "USERNAME + " + prefix
                + "PASSWORD) with " + prefix + "HOST=" + url.getHost() + ".";
        if (keys.isEmpty()) {
            report.warning("repository `" + name + "` takes a credential (" + credential.kind() + ") the build reads"
                    + " from no environment variable jk can name; run `jk repo login " + name + "`." + ci);
            return;
        }
        machineRepositories.add(new UserConfigEditor.RepositoryTable(name, url, groups, keys));
        report.warning("repository `" + name + "` takes the credential the build reads from "
                + String.join(" and ", keys.values()) + ": a project's jk.toml may not read that variable, so"
                + " jk import declares [repositories." + name + "] at the same URL with the reference in"
                + " ~/.jk/config.toml." + ci);
    }

    /**
     * The jk name of a repository Gradle names {@code gradleName}: lower-case, {@code -} for
     * anything else; {@code repoN} for Gradle's generated {@code maven}, {@code maven2}, … and for a
     * name jk reserves or another repository already took.
     */
    private String repositoryName(String gradleName) {
        String name = gradleName
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        boolean taken = repositories.values().stream().anyMatch(r -> r.name().equals(name));
        if (name.isEmpty() || name.matches("maven\\d*") || RESERVED_REPOSITORY_NAMES.contains(name) || taken) {
            return "repo" + (repositories.size() + 1);
        }
        return name;
    }

    /** Repository names jk gives its own remotes and store. */
    private static final Set<String> RESERVED_REPOSITORY_NAMES = Set.of(
            RepositorySpec.CENTRAL, RepositorySpec.GOOGLE, RepositorySpec.JUMPKICK_NAME, RepositorySpec.JK_LOCAL);
}
