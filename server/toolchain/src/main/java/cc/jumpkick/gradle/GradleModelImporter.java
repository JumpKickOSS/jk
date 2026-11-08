// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.ImportedKotlin;
import cc.jumpkick.compat.NodeImportMapping;
import cc.jumpkick.compat.RelocationRules;
import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.groovy.GroovyResolver;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.Languages;
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
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
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

    /** The Avro Gradle plugin and its base plugin. */
    static final String AVRO_PLUGIN = "com.github.davidmc24.gradle.plugin.avro";

    static final String AVRO_BASE_PLUGIN = "com.github.davidmc24.gradle.plugin.avro-base";

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
            AVRO_PLUGIN,
            AVRO_BASE_PLUGIN,
            XJC_PLUGIN,
            GradleSchemaRegistry.PLUGIN);

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
            "java-test-fixtures",
            AVRO_PLUGIN,
            AVRO_BASE_PLUGIN,
            XJC_PLUGIN,
            GradleSchemaRegistry.PLUGIN);

    /** Plugin class-name prefixes the probed ids already account for. */
    private static final List<String> MAPPED_PLUGIN_CLASSES = List.of(
            // Its arguments reach [javac] args (or [nullness]) through the compile tasks.
            "net.ltgt.gradle.errorprone",
            "org.jetbrains.kotlin.gradle",
            "org.springframework.boot.gradle",
            "io.spring.gradle.dependencymanagement",
            "org.jetbrains.dokka",
            "com.google.devtools.ksp",
            "com.gorylenko",
            "io.quarkus",
            "com.diffplug.gradle.spotless",
            "com.github.jengelman.gradle.plugins.shadow",
            "com.github.bjornvester.xjc",
            "com.github.davidmc24.gradle.plugin.avro",
            "com.github.imflog.schema.registry");

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
    private final Map<String, PluginRules.Rule> importRules;
    /** Gradle project path → the jk module name the sibling edge uses. */
    private final Map<String, String> namesByPath = new LinkedHashMap<>();

    private final Map<String, RepositorySpec> repositories = new LinkedHashMap<>();
    /** The {@code ~/.jk/config.toml} tables that carry the credentials the build reads from the environment. */
    private final List<UserConfigEditor.CredentialTable> machineCredentials = new ArrayList<>();

    /** Every repository whose credential the build reads, which CI sends through its own variables. */
    private final Set<String> credentialed = new LinkedHashSet<>();
    /** The workspace root's {@code java} and {@code jdk}, which a member inherits rather than repeats; 0 for none. */
    private int rootJava;

    private int rootJdk;

    /** Whether the build asks Maven Central before any other repository it declares. */
    private boolean centralFirst;

    /**
     * The repositories in the build's order. Central is jk's after every declared one, so it is
     * written first when the build asks it first: the others are not asked for what Central serves.
     */
    private List<RepositorySpec> declaredRepositories() {
        List<RepositorySpec> out = new ArrayList<>();
        if (centralFirst && !repositories.isEmpty()) out.add(RepositorySpec.MAVEN_CENTRAL);
        out.addAll(repositories.values());
        return out;
    }

    /** Whether the {@code mavenLocal()} row was written; one row however many projects declare it. */
    private boolean mavenLocal;

    private GradleModelImporter(GradleModel model, Path buildRoot, RefreshVersions refreshVersions) {
        this.model = model;
        this.buildRoot = buildRoot;
        this.refreshVersions = refreshVersions;
        try {
            this.importRules = PluginRules.forBuild(buildRoot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The plugin ids the model query asks each project of the build at {@code buildRoot} about. */
    static List<String> probedPluginIds(Path buildRoot) throws IOException {
        Set<String> ids = new LinkedHashSet<>(KNOWN_PLUGINS);
        ids.addAll(PluginRules.forBuild(buildRoot).keySet());
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
                report.warning(p.path() + " (" + p.dir() + ") is a java-platform BOM, not a module; publish it with"
                        + " jk export bom if needed");
            } else if (!p.hasJvmSources() && p.configurations().isEmpty()) {
                skipped.add(p.path());
            } else {
                members.add(p);
            }
        }
        if (!skipped.isEmpty()) {
            report.warning(String.join(", ", skipped) + ": no JVM plugin, nothing declared; not imported as modules");
        }
        for (GradleModel.Repository r : model.settingsRepositories()) repository(r);
        if (members.isEmpty()) {
            for (GradleModel.Repository r : root.repositories()) repository(r);
            JkBuild single =
                    memberBuild(root, GradleModuleSettings.artifactName(root, report), "", declaredRepositories());
            return new GradleBuildImport.Result(single, Map.of(), finish(), machineCredentials, true);
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
            rootRows.warning("root project " + root.name() + " has its own sources or dependencies; move them into a"
                    + " module");
        }
        reportPlugins(root, new LinkedHashSet<>(root.plugins()), importRules, rootRows);
        reportTasks(root, rootRows);
        for (GradleModel.Repository r : root.repositories()) repository(r);
        rows.addAll("", rootRows.build());
        JkBuild rootBuild = JkBuild.builder(rootProject)
                .workspace(new Workspace(
                        members.stream().map(GradleModel.Project::dir).toList()))
                .repositories(declaredRepositories())
                .build();
        return new GradleBuildImport.Result(rootBuild, modules, finish(), machineCredentials, true);
    }

    private ImportReport finish() {
        rows.flush(report);
        // CI has no ~/.jk/config.toml: each repository takes its credential from JK_REPO_<ID>_*.
        if (!credentialed.isEmpty()) {
            report.warning("on CI, set JK_REPO_<ID>_* for " + String.join(", ", credentialed));
        }
        return report.build();
    }

    /**
     * The jk name of each member: its Gradle project name, unless two members share one, in which
     * case each is named by its root-relative path with {@code /} as {@code -}, and a row says so.
     */
    private void nameMembers(List<GradleModel.Project> members) {
        Map<String, List<GradleModel.Project>> byName = new TreeMap<>();
        for (GradleModel.Project p : members)
            byName.computeIfAbsent(GradleModuleSettings.artifactName(p, report), k -> new ArrayList<>())
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
            report.warning(String.join(
                            ", ",
                            carriers.stream().map(GradleModel.Project::dir).toList())
                    + " share the name " + e.getKey() + "; named by path instead: " + String.join(", ", renamed));
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
        boolean platformed = bomPlatforms(p, applied, deps, local);
        for (GradleModel.Configuration c : p.configurations()) mapConfiguration(p, c, deps, local, platformed);
        for (GradleModel.Repository r : p.repositories()) repository(r);
        List<PluginConfig> plugins = new ArrayList<>(PluginRules.map(
                applied,
                p.pluginVersions(),
                p.ruled(),
                buildRoot.resolve(p.dir()),
                importRules,
                local,
                this::ruleCredential));
        GradleModel.Xjc xjc = p.xjc();
        if (xjc != null) plugins.add(GradleSchemaTables.jaxb(xjc, local));
        GradleModel.SchemaRegistry sr = p.schemaRegistry();
        GradleSchemaRegistry.Mapped registry = sr == null
                ? GradleSchemaRegistry.Mapped.NONE
                : GradleSchemaRegistry.map(sr, local, this::ruleCredential);
        if (registry.table() != null) plugins.add(registry.table());
        GradleModel.Avro avro = p.avro();
        boolean fromRegistry = plugins.stream().anyMatch(c -> c.id().equals("schema-registry"));
        if (avro != null) plugins.add(GradleSchemaTables.avro(p, avro, fromRegistry, registry.outputs(), local));
        plugins = merged(plugins);
        String groovy = applied.contains("groovy") ? groovyVersion(p, local) : null;
        Set<String> unreported = new LinkedHashSet<>(applied);
        unreported.remove("groovy");
        reportPlugins(p, unreported, importRules, local);
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
                .description(p.description() != null ? p.description() : GradleModuleSettings.pomDescription(p));
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
        BuildBlock build = BuildBlock.EMPTY.withFixtures(fixturesRoot(p, applied, local));
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
            local.warning("Shadow relocate " + String.join(", ", relocations.unmapped())
                    + " not written; those classes keep their own names");
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
        jkBuild = GradleModuleSettings.compilerArgs(p, jkBuild, local);
        GradleModuleSettings.suggestNullness(p, buildRoot.resolve(p.dir()), jkBuild, local);
        jkBuild = GradleModuleSettings.testJvm(p, jkBuild);
        jkBuild = GradleModuleSettings.publication(p, jkBuild);
        Path projectDir = buildRoot.resolve(p.dir());
        NodeImportMapping.Mapped node = GradleNodeImport.map(projectDir, applied, local);
        if (node == null) node = NodeImportMapping.quinoa(projectDir, local);
        jkBuild = NodeImportMapping.apply(jkBuild, node);
        rows.addAll(modulePath, local.build());
        return jkBuild;
    }

    /**
     * The {@code [test] fixtures} root of a project that applies {@code java-test-fixtures}: its
     * {@code testFixtures} source set's Java root, read where it is. {@code null} without the plugin.
     */
    private static @Nullable String fixturesRoot(
            GradleModel.Project p, Set<String> applied, ImportReport.Builder local) {
        if (!applied.contains("java-test-fixtures")) return null;
        GradleModel.SourceSet fixtures = p.sourceSets().stream()
                .filter(s -> s.name().equals("testFixtures"))
                .findFirst()
                .orElse(null);
        List<String> java = fixtures == null ? List.of() : fixtures.java();
        String root = java.isEmpty() ? "src/testFixtures/java" : java.getFirst();
        if (java.size() > 1) {
            local.warning("testFixtures: imported " + root + " only");
        }
        if (fixtures != null && !fixtures.kotlin().stream().allMatch(java::contains)) {
            local.warning("testFixtures: Kotlin sources not imported");
        }
        return root;
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
            local.warning("groovy: no version found; wrote groovy = \"" + GroovyResolver.DEFAULT_VERSION
                    + "\". Set the version the build uses");
            return GroovyResolver.DEFAULT_VERSION;
        }
        if (GroovyResolver.belowFloor(version)) {
            local.warning(GroovyResolver.floorNote(version));
            return GroovyResolver.DEFAULT_VERSION;
        }
        return version;
    }

    /** One table per plugin, the keys of every config for it in order. */
    private static List<PluginConfig> merged(List<PluginConfig> configs) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (PluginConfig c : configs)
            byId.computeIfAbsent(c.id(), k -> new LinkedHashMap<>()).putAll(c.values());
        List<PluginConfig> out = new ArrayList<>();
        byId.forEach((id, values) -> out.add(new PluginConfig(id, values)));
        return out;
    }

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
                local.warning("BOM " + bom + ": version reads an undefined property; declare it in"
                        + " [platform-dependencies]");
                continue;
            }
            String[] parts = bom.split(":");
            if (parts.length != 3) {
                local.warning("BOM " + bom + " is not group:artifact:version; declare it in [platform-dependencies]");
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
                local.warning("configuration " + c.name() + " has no jk table; declare " + String.join(", ", coords)
                        + " under the right scope");
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
            local.warning("testCompileOnly "
                    + String.join(
                            ", ",
                            c.dependencies().stream()
                                    .map(GradleModel.Dependency::module)
                                    .toList())
                    + " went to [test-dependencies], so also on the test runtime");
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
                // A project's own fixtures and main are on its tests already; java-test-fixtures
                // also declares testFixturesApi(project(self)).
                if (p.path().equals(d.projectPath())) return;
                String sibling = d.projectPath() == null ? null : namesByPath.get(d.projectPath());
                if (sibling == null) {
                    local.warning("project " + d.projectPath() + " (" + configuration + ") is not a workspace module;"
                            + " dropped");
                    return;
                }
                boolean fixtures = d.fixtures() && scope == Scope.TEST;
                if (d.fixtures() && !fixtures) {
                    local.warning(
                            configuration + ": testFixtures(" + d.projectPath() + ") imported as " + d.projectPath());
                }
                Dependency edge = Dependency.workspace(sibling).withFixtures(fixtures);
                if (!d.excludes().isEmpty()) edge = edge.withExclusions(exclusions(d));
                addWorkspaceEdge(deps.computeIfAbsent(scope, s -> new ArrayList<>()), edge);
                if (fixtures) fixturesApi(p, d.projectPath(), deps, local, platformed);
            }
            case "module" -> mapModule(p, scope, d, deps, local, platformed);
            case "files" ->
                local.warning("file dependency " + String.join(", ", d.files()) + " (" + configuration
                        + ") has no coordinate; dropped");
            default ->
                local.warning((d.text() == null ? d.kind() : d.text()) + " (" + configuration
                        + ") is not a coordinate or project; dropped");
        }
    }

    /**
     * Add {@code edge} to {@code scope}, folded into an edge on the same sibling already there: one
     * entry per sibling, with fixtures when either declaration asked for them.
     */
    private static void addWorkspaceEdge(List<Dependency> scope, Dependency edge) {
        for (int i = 0; i < scope.size(); i++) {
            Dependency existing = scope.get(i);
            if (!existing.isWorkspace() || !existing.library().equals(edge.library())) continue;
            if (edge.fixtures() && !existing.fixtures()) scope.set(i, existing.withFixtures(true));
            return;
        }
        scope.add(edge);
    }

    /**
     * The libraries the producer's {@code testFixturesApi} exports, onto the consumer's test scope:
     * Gradle puts them on every fixtures consumer's classpath, and a jk fixtures edge does not carry
     * the producer's test dependencies.
     */
    private void fixturesApi(
            GradleModel.Project consumer,
            @Nullable String producerPath,
            Map<Scope, List<Dependency>> deps,
            ImportReport.Builder local,
            boolean platformed) {
        GradleModel.Project producer = model.projects().stream()
                .filter(q -> q.path().equals(producerPath))
                .findFirst()
                .orElse(null);
        if (producer == null) return;
        List<Dependency> test = deps.computeIfAbsent(Scope.TEST, s -> new ArrayList<>());
        for (GradleModel.Configuration c : producer.configurations()) {
            if (!c.name().equals("testFixturesApi")) continue;
            for (GradleModel.Dependency d : c.dependencies()) {
                if (!d.kind().equals("module") || d.isPlatform()) continue;
                if (test.stream().anyMatch(e -> e.module().equals(d.module()))) continue;
                mapModule(consumer, Scope.TEST, d, deps, local, platformed);
            }
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
            local.error(d.module() + " lacks a group or artifact; dropped");
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
                local.warning(module + " " + managed + " from dependencyManagement { }; pinned exactly");
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
            local.warning(module + " " + version + " is dynamic; wrote latest, pinned by jk lock");
            dep = Dependency.of(shortName, module, VersionSelector.parse("latest"));
        } else if (platformed && managedAbove(p, module, version)) {
            // Gradle's dependency-management lets a declared version undercut the BOM; a jk pin below
            // the version the platform aligns the rest of the graph on does not resolve.
            local.warning(module + " " + version + " is below its BOM's "
                    + p.managedVersions().get(module) + "; wrote managed");
            dep = Dependency.platformManaged(shortName, module);
        } else {
            dep = Dependency.of(shortName, module, VersionSelector.parse(raised(p, d, version, local)));
        }
        if (d.classifier() != null && !d.classifier().isBlank()) dep = dep.withClassifier(d.classifier());
        if (d.type() != null && !d.type().isBlank()) {
            local.warning(module + ": artifact type " + d.type() + " dropped; jk uses the jar");
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
        local.warning(d.module() + " " + version + " resolves to " + resolved + " in Gradle; wrote " + resolved);
        return resolved;
    }

    private static List<String> exclusions(GradleModel.Dependency d) {
        List<String> out = new ArrayList<>(d.excludes());
        if (d.intransitive() && !out.contains("*:*")) out.add("*:*");
        return out;
    }

    // --- rows ---------------------------------------------------------------

    private static void reportPlugins(
            GradleModel.Project p,
            Set<String> applied,
            Map<String, PluginRules.Rule> rules,
            ImportReport.Builder local) {
        for (String id : applied) {
            if (SILENT_PLUGINS.contains(id) || rules.containsKey(id)) continue;
            switch (id) {
                case "groovy", "scala" ->
                    local.warning(id + ": set project." + id + " to the build's compiler version");
                case "checkstyle", "pmd" -> local.warning(id + ": configure [lint] by hand");
                default -> local.warning("Gradle plugin " + id + ": not imported");
            }
        }
        for (String cls : p.pluginClasses()) {
            if (MAPPED_PLUGIN_CLASSES.stream().anyMatch(cls::startsWith)) continue;
            local.warning("Gradle plugin " + cls + ": not imported");
        }
    }

    private static void reportTasks(GradleModel.Project p, ImportReport.Builder local) {
        for (GradleModel.Task t : p.tasks()) {
            String type = t.type().substring(t.type().lastIndexOf('.') + 1);
            local.warning("task " + t.name() + " (" + type + (t.group().isBlank() ? "" : ", group " + t.group())
                    + ") not translated; port it to a .jk/ build script if needed");
        }
    }

    private void reportSourceSets(GradleModel.Project p, ImportReport.Builder local) {
        for (GradleModel.SourceSet s : p.sourceSets()) {
            if (s.name().equals("testFixtures")) {
                Path dir = buildRoot.resolve(p.dir());
                if (s.resources().stream().anyMatch(r -> holdsFiles(dir.resolve(r)))) {
                    local.warning("testFixtures: resources not imported");
                }
                continue;
            }
            if (!s.name().equals("main") && !s.name().equals("test")) {
                local.warning("source set " + s.name() + " has no jk equivalent; sources not imported");
                continue;
            }
            List<String> odd = new ArrayList<>();
            String prefix = "src/" + s.name() + "/";
            // The Avro plugin's own output, which [avro] generates in its place.
            GradleModel.Avro avro = p.avro();
            String avroOut = avro == null || avro.buildDir() == null
                    ? null
                    : avro.buildDir() + "/generated-" + s.name() + "-avro-java";
            for (String dir : s.java())
                if (!dir.equals(prefix + "java") && !dir.equals(avroOut) && !writtenByMapped(p, dir)) odd.add(dir);
            for (String dir : s.kotlin())
                if (!dir.equals(prefix + "kotlin") && !dir.equals(prefix + "java")) odd.add(dir);
            for (String dir : s.resources()) if (!dir.equals(prefix + "resources")) odd.add(dir);
            if (!odd.isEmpty()) {
                local.warning("source set " + s.name() + " reads "
                        + String.join(", ", odd.stream().map(p::withWriters).toList())
                        + ", outside jk's layout; move them or generate them");
            }
        }
    }

    /** Whether a task of a plugin an import rule maps writes {@code dir}: that table generates it. */
    private boolean writtenByMapped(GradleModel.Project p, String dir) {
        for (GradleModel.Producer w : p.producers().getOrDefault(dir, List.of())) {
            if (w.plugin() != null && importRules.containsKey(w.plugin())) return true;
        }
        return false;
    }

    /** Whether a regular file sits anywhere under {@code dir}. */
    private static boolean holdsFiles(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try {
            return PathUtil.anyRegularFile(dir, d -> false, f -> true);
        } catch (IOException unreadable) {
            return false;
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
        if (GradleImporter.isCentral(url) && repositories.isEmpty()) centralFirst = true;
        if (url.isBlank() || GradleImporter.isCentral(url)) return;
        if (url.startsWith("file:") && url.replace('\\', '/').matches(".*/\\.m2/repository/?")) {
            if (mavenLocal) return;
            mavenLocal = true;
            report.warning("mavenLocal() ignored; jk reads ~/.m2 already");
            return;
        }
        String normalized = url.endsWith("/") ? url : url + "/";
        if (repositories.containsKey(normalized)) return;
        URI uri;
        try {
            uri = RepositorySpec.normalizedUrl(new URI(normalized));
        } catch (URISyntaxException e) {
            report.warning("repository URL " + url + " is not a valid URI; skipped");
            return;
        }
        String name = repositoryName(repo.name());
        repositories.put(
                normalized,
                new RepositorySpec(name, uri, null, null, repo.groups()).withPolicy(repo.releases(), repo.snapshots()));
        if (!repo.unmappedContent().isEmpty()) {
            report.warning(name + ": content filters " + String.join(", ", repo.unmappedContent())
                    + " not written; jk binds whole groups");
        }
        GradleModel.Credential credential = repo.credential();
        if (credential != null) credential(name, uri, credential);
    }

    private void credential(String name, URI url, GradleModel.Credential credential) {
        String secretEnv = credential.secretEnv();
        Map<String, String> keys = new LinkedHashMap<>();
        switch (credential.kind()) {
            case "header" -> {
                if (secretEnv != null) keys.put("token", envReference(secretEnv));
            }
            case "password" -> {
                String usernameEnv = credential.usernameEnv();
                String username = usernameEnv != null ? envReference(usernameEnv) : credential.username();
                if (secretEnv != null && username != null) {
                    keys.put("username", username);
                    keys.put("password", envReference(secretEnv));
                }
            }
            default -> {}
        }
        credentialed.add(name);
        if (keys.isEmpty()) {
            report.warning(name + ": no credential found; run jk repo login " + name);
            return;
        }
        machineCredentials.add(new UserConfigEditor.CredentialTable(name, url, keys));
        report.warning(name + ": credential added to ~/.jk/config.toml");
    }

    /** A credential an import rule found, once per repository id however many modules name it. */
    private void ruleCredential(PluginRules.Credential c) {
        if (credentialed.contains(c.id())) return;
        credential(
                c.id(),
                URI.create(c.url()),
                new GradleModel.Credential("password", c.passwordEnv(), c.usernameEnv(), null));
    }

    private static String envReference(String variable) {
        return "${env:" + variable + "}";
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
