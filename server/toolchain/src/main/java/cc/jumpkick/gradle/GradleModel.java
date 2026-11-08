// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.RelocationRules;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.version.Versions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The project model Gradle writes for {@code jk import} (the init script {@code
 * jk-import-model.init.gradle}): the build, each of its projects, and per project the plugins it
 * applies, the dependencies each configuration declares, its toolchain, source roots, the BOMs its
 * {@code dependencyManagement} block imports and the tasks its scripts register. Read from the
 * JSON the fork leaves behind.
 */
record GradleModel(
        String gradleVersion, String rootName, List<Repository> settingsRepositories, List<Project> projects) {

    /**
     * A Maven repository a project or the settings declare: {@code groups} are the groups its
     * {@code content { }} filter includes ({@code g.*} for a group with its subgroups), {@code
     * unmappedContent} the filters that name less than a whole group.
     */
    record Repository(
            String url,
            String name,
            List<String> groups,
            List<String> unmappedContent,
            @Nullable Credential credential,
            /** False under {@code mavenContent { snapshotsOnly() }}. */
            boolean releases,
            /** False under {@code mavenContent { releasesOnly() }}. */
            boolean snapshots) {}

    /**
     * A repository's credential as the names of the environment variables holding it, never its
     * value. {@code kind} is {@code header} (one secret), {@code password} (a username and a
     * secret) or the Gradle type of another; {@code username} is a literal username no variable
     * holds. A null name is a value no variable of the build's environment holds.
     */
    record Credential(
            String kind,
            @Nullable String secretEnv,
            @Nullable String usernameEnv,
            @Nullable String username) {}

    /** One Gradle project; {@code dir} is root-relative with {@code /} separators, empty for the root. */
    record Project(
            String path,
            String name,
            String dir,
            String group,
            String version,
            @Nullable String description,
            List<String> plugins,
            List<String> pluginClasses,
            Map<String, String> pluginVersions,
            @Nullable Java java,
            @Nullable String kotlinVersion,
            /** The {@code compileKotlin} task's {@code jvmTarget} as a major; {@code 0} when unset. */
            int kotlinJvmTarget,
            @Nullable String mainClass,
            boolean bootBuildInfo,
            Map<String, String> manifest,
            List<Repository> repositories,
            List<SourceSet> sourceSets,
            List<Configuration> configurations,
            Map<String, String> managedVersions,
            @Nullable String springBootBom,
            List<String> importedBoms,
            List<Task> tasks,
            /** The {@code shadowJar} task's relocators, in order; empty without the Shadow plugin. */
            List<RelocationRules.Relocation> relocations,
            /** The xjc plugin's configuration; null when the project does not apply it. */
            @Nullable Xjc xjc,
            /** The Avro plugin's configuration; null when the project does not apply it. */
            @Nullable Avro avro,
            /** {@code group:artifact} → the highest version the project's classpaths resolve it to. */
            Map<String, String> resolved,
            /** What {@code compileJava} hands javac, argument providers included; null without the task. */
            @Nullable List<String> mainCompilerArgs,
            /** What {@code compileTestJava} hands javac; null without the task. */
            @Nullable List<String> testCompilerArgs,
            /** The {@code test} task's own JVM settings; null without the task. */
            @Nullable TestTask test,
            /** {@code base.archivesName}; null without the base plugin. */
            @Nullable String archivesName,
            List<Publication> publications,
            /** Per Gradle plugin id, the properties its import rule asked the init script to read. */
            Map<String, Map<String, Object>> ruled,
            /** Per source or schema directory under the build directory, the tasks that write it. */
            Map<String, List<Producer>> producers) {

        boolean isRoot() {
            return dir.isEmpty();
        }

        /** The tasks known to write {@code dir}, as {@code task y (plugin)}; null when none is. */
        @Nullable
        String writersOf(String dir) {
            List<Producer> writers = producers.getOrDefault(dir, List.of());
            return writers.isEmpty()
                    ? null
                    : String.join(", ", writers.stream().map(Producer::describe).toList());
        }

        /** {@code dir}, followed by the tasks known to write it in parentheses. */
        String withWriters(String dir) {
            String writers = writersOf(dir);
            return writers == null ? dir : dir + " (" + writers + ")";
        }

        boolean applies(String pluginId) {
            return plugins.contains(pluginId);
        }

        /** A project that compiles something: any JVM language plugin, or a declared dependency. */
        boolean hasJvmSources() {
            for (String jvm : JVM_PLUGINS) {
                if (plugins.contains(jvm)) return true;
            }
            return false;
        }
    }

    /** The plugins whose application makes a project a compiled module. */
    static final List<String> JVM_PLUGINS = List.of(
            "java",
            "java-library",
            "application",
            "war",
            "groovy",
            "scala",
            "org.jetbrains.kotlin.jvm",
            "org.jetbrains.kotlin.multiplatform");

    /** The java extension: toolchain language version, source/target compatibility, {@code --release}. */
    /** {@code vendor} is the toolchain's {@code JvmVendorSpec} when the build names one, else null. */
    record Java(int toolchain, @Nullable String vendor, int source, int target, int release) {}

    /**
     * The {@code com.github.bjornvester.xjc} extension: the schema directory, the one package the
     * generated classes go to (null for the namespace-derived ones), binding files and xjc options.
     * Paths are project-relative.
     */
    record Xjc(
            @Nullable String xsdDir,
            @Nullable String defaultPackage,
            List<String> bindingFiles,
            List<String> options) {}

    /**
     * The {@code com.github.davidmc24.gradle.plugin.avro} extension and its {@code generateAvroJava}
     * task: the directories it reads schemas from and the project's build directory, both
     * project-relative. Its generator options are the plugin's import rule's to read.
     */
    record Avro(List<String> sources, @Nullable String buildDir) {}

    /**
     * A task whose outputs hold a generated directory: its name, declared type and the id of the
     * plugin whose jar holds the type, null when no applied plugin's does.
     */
    record Producer(String task, String type, @Nullable String plugin) {

        /** {@code task sharedSchemasLoader (kafka-helper)}: the plugin id's last segment. */
        String describe() {
            return "task " + task + (plugin == null ? "" : " (" + plugin.substring(plugin.lastIndexOf('.') + 1) + ")");
        }
    }

    record SourceSet(String name, List<String> java, List<String> resources, List<String> kotlin) {}

    record Configuration(String name, List<Dependency> dependencies, List<Constraint> constraints) {}

    /**
     * One declared dependency. {@code kind} is {@code module} (Maven coordinates), {@code project}
     * (a sibling, {@code projectPath}), {@code files} or {@code other}. {@code version} is the
     * declared version, empty when the declaration carried none; {@code strict}/{@code
     * required}/{@code prefer} are the rich-version parts; {@code category} is {@code platform} or
     * {@code enforced-platform} for a BOM; {@code fixtures} marks {@code testFixtures(project(...))}.
     */
    record Dependency(
            String kind,
            String group,
            String artifact,
            String version,
            @Nullable String strict,
            @Nullable String required,
            @Nullable String prefer,
            @Nullable String category,
            boolean intransitive,
            List<String> excludes,
            @Nullable String classifier,
            @Nullable String type,
            @Nullable String projectPath,
            boolean fixtures,
            List<String> files,
            @Nullable String text) {

        String module() {
            return group + ":" + artifact;
        }

        /** The version to write: the strict one, else the required one, else the declared or preferred one. */
        String effectiveVersion() {
            if (strict != null && !strict.isBlank()) return strict;
            if (required != null && !required.isBlank()) return required;
            if (!version.isBlank()) return version;
            return prefer == null ? "" : prefer;
        }

        boolean isPlatform() {
            return category != null && category.endsWith("platform");
        }
    }

    record Constraint(String module, String version) {}

    record Task(String name, String type, String group) {}

    // The keys the init script writes. Spelled here, and only here, on the Java side so the reader
    // and its writer — Groovy, in jk-import-model.init.gradle — are paired by name.
    static final String BOOT_BUILD_INFO = "bootBuildInfo";
    static final String CATEGORY = "category";
    static final String CLASSIFIER = "classifier";
    static final String CONFIGURATIONS = "configurations";
    static final String CONSTRAINTS = "constraints";
    static final String DEPENDENCIES = "dependencies";
    static final String DESCRIPTION = "description";
    static final String EXCLUDES = "excludes";
    static final String FILES = "files";
    static final String FIXTURES = "fixtures";
    static final String GRADLE = "gradle";
    static final String IMPORTED_BOMS = "importedBoms";
    static final String INCLUDES = "includes";
    static final String INTRANSITIVE = "intransitive";
    static final String JAVA = "java";
    static final String KOTLIN = "kotlin";
    static final String KOTLIN_JVM_TARGET = "kotlinJvmTarget";
    static final String KOTLIN_VERSION = "kotlinVersion";
    static final String MAIN_CLASS = "mainClass";
    static final String MANAGED_VERSIONS = "managedVersions";
    static final String MANIFEST = "manifest";
    static final String PLUGIN_CLASSES = "pluginClasses";
    static final String PLUGIN_VERSIONS = "pluginVersions";
    static final String PLUGINS = "plugins";
    static final String PREFER = "prefer";
    static final String PROJECT_PATH = "projectPath";
    static final String PROJECTS = "projects";
    static final String RAW_STRING = "rawString";
    static final String RELEASE = "release";
    static final String RELOCATIONS = "relocations";
    static final String RESOLVED = "resolved";
    static final String REPOSITORIES = "repositories";
    static final String ARCHIVES_NAME = "archivesName";
    static final String ARTIFACT_ID = "artifactId";
    static final String COMPILE_JAVA = "compileJava";
    static final String COMPILE_TEST_JAVA = "compileTestJava";
    static final String COMPILER_ARGS = "compilerArgs";
    static final String GROUP_ID = "groupId";
    static final String JVM_ARGS = "jvmArgs";
    static final String POM_DESCRIPTION = "pomDescription";
    static final String POM_NAME = "pomName";
    static final String POM_URL = "pomUrl";
    static final String PUBLICATIONS = "publications";
    static final String SYSTEM_PROPERTIES = "systemProperties";
    static final String TEST = "test";
    static final String VENDOR = "vendor";
    static final String CREDENTIAL = "credential";
    static final String RELEASES = "releases";
    static final String SNAPSHOTS = "snapshots";
    static final String GROUPS = "groups";
    static final String KIND = "kind";
    static final String NAME = "name";
    static final String SECRET_ENV = "secretEnv";
    static final String UNMAPPED_CONTENT = "unmappedContent";
    static final String URL = "url";
    static final String USERNAME = "username";
    static final String USERNAME_ENV = "usernameEnv";
    static final String REQUIRED = "required";
    static final String RESOURCES = "resources";
    static final String ROOT_NAME = "rootName";
    static final String SETTINGS_REPOSITORIES = "settingsRepositories";
    static final String SHADED_PATTERN = "shadedPattern";
    static final String SOURCE_COMPATIBILITY = "sourceCompatibility";
    static final String SOURCE_SETS = "sourceSets";
    static final String SPRING_BOOT_BOM = "springBootBom";
    static final String STRICT = "strict";
    static final String TARGET_COMPATIBILITY = "targetCompatibility";
    static final String TASKS = "tasks";
    static final String TEXT = "text";
    static final String TOOLCHAIN = "toolchain";
    static final String TYPE = "type";
    static final String XJC = "xjc";
    static final String AVRO = "avro";
    static final String AVRO_SOURCES = "sources";
    static final String AVRO_BUILD_DIR = "buildDir";
    static final String RULED = "ruled";
    static final String PRODUCERS = "producers";

    /** The {@code test} task's system properties with string values, and its JVM arguments. */
    record TestTask(Map<String, String> systemProperties, List<String> jvmArgs) {}

    /** A {@code MavenPublication}: its coordinates and the POM metadata it sets. */
    record Publication(
            String name,
            String groupId,
            String artifactId,
            @Nullable String pomName,
            @Nullable String pomDescription,
            @Nullable String pomUrl) {}

    /** Parse the JSON the init script wrote. */
    static GradleModel parse(String json) {
        List<Project> projects = new ArrayList<>();
        for (String p : Jsonl.objectArray(json, PROJECTS)) projects.add(parseProject(p));
        return new GradleModel(
                Jsonl.topStr(json, GRADLE) == null ? "" : Jsonl.requiredStr(json, GRADLE),
                Jsonl.topStr(json, ROOT_NAME) == null ? "" : Jsonl.requiredStr(json, ROOT_NAME),
                repositories(json, SETTINGS_REPOSITORIES),
                projects);
    }

    private static List<Repository> repositories(String json, String key) {
        List<Repository> out = new ArrayList<>();
        for (String r : Jsonl.objectArray(json, key)) {
            String c = Jsonl.nested(r, CREDENTIAL);
            out.add(new Repository(
                    str(r, URL),
                    str(r, NAME),
                    Jsonl.strArray(r, GROUPS),
                    Jsonl.strArray(r, UNMAPPED_CONTENT),
                    c == null
                            ? null
                            : new Credential(
                                    str(c, KIND),
                                    Jsonl.topStr(c, SECRET_ENV),
                                    Jsonl.topStr(c, USERNAME_ENV),
                                    Jsonl.topStr(c, USERNAME)),
                    Jsonl.bool(r, RELEASES, true),
                    Jsonl.bool(r, SNAPSHOTS, true)));
        }
        return out;
    }

    private static Project parseProject(String p) {
        List<SourceSet> sourceSets = new ArrayList<>();
        for (String s : Jsonl.objectArray(p, SOURCE_SETS)) {
            sourceSets.add(new SourceSet(
                    str(s, "name"), Jsonl.strArray(s, JAVA), Jsonl.strArray(s, RESOURCES), Jsonl.strArray(s, KOTLIN)));
        }
        List<Configuration> configurations = new ArrayList<>();
        for (String c : Jsonl.objectArray(p, CONFIGURATIONS)) {
            List<Dependency> deps = new ArrayList<>();
            for (String d : Jsonl.objectArray(c, DEPENDENCIES)) deps.add(parseDependency(d));
            List<Constraint> constraints = new ArrayList<>();
            for (String k : Jsonl.objectArray(c, CONSTRAINTS)) {
                constraints.add(new Constraint(str(k, "module"), str(k, "version")));
            }
            configurations.add(new Configuration(str(c, "name"), deps, constraints));
        }
        List<Task> tasks = new ArrayList<>();
        for (String t : Jsonl.objectArray(p, TASKS))
            tasks.add(new Task(str(t, "name"), str(t, "type"), str(t, "group")));
        List<RelocationRules.Relocation> relocations = new ArrayList<>();
        for (String r : Jsonl.objectArray(p, RELOCATIONS)) {
            String pattern = Jsonl.topStr(r, "pattern");
            if (pattern == null) continue;
            relocations.add(new RelocationRules.Relocation(
                    pattern,
                    Jsonl.topStr(r, SHADED_PATTERN),
                    Jsonl.bool(r, RAW_STRING, false),
                    Jsonl.strArray(r, INCLUDES),
                    Jsonl.strArray(r, EXCLUDES)));
        }
        String compilerArgs = Jsonl.nested(p, COMPILER_ARGS);
        String testJson = Jsonl.nested(p, TEST);
        List<Publication> publications = new ArrayList<>();
        for (String pub : Jsonl.objectArray(p, PUBLICATIONS)) {
            publications.add(new Publication(
                    str(pub, NAME),
                    str(pub, GROUP_ID),
                    str(pub, ARTIFACT_ID),
                    Jsonl.topStr(pub, POM_NAME),
                    Jsonl.topStr(pub, POM_DESCRIPTION),
                    Jsonl.topStr(pub, POM_URL)));
        }
        String javaJson = Jsonl.nested(p, JAVA);
        Java java = javaJson == null
                ? null
                : new Java(
                        Jsonl.intValue(javaJson, TOOLCHAIN, 0),
                        Jsonl.topStr(javaJson, VENDOR),
                        major(Jsonl.topStr(javaJson, SOURCE_COMPATIBILITY)),
                        major(Jsonl.topStr(javaJson, TARGET_COMPATIBILITY)),
                        Jsonl.intValue(javaJson, RELEASE, 0));
        return new Project(
                str(p, "path"),
                str(p, "projectName"),
                str(p, "dir"),
                str(p, "group"),
                str(p, "version"),
                Jsonl.topStr(p, DESCRIPTION),
                Jsonl.strArray(p, PLUGINS),
                Jsonl.strArray(p, PLUGIN_CLASSES),
                Jsonl.strMap(p, PLUGIN_VERSIONS),
                java,
                Jsonl.topStr(p, KOTLIN_VERSION),
                major(Jsonl.topStr(p, KOTLIN_JVM_TARGET)),
                Jsonl.topStr(p, MAIN_CLASS),
                Jsonl.bool(p, BOOT_BUILD_INFO, false),
                Jsonl.strMap(p, MANIFEST),
                repositories(p, REPOSITORIES),
                sourceSets,
                configurations,
                Jsonl.strMap(p, MANAGED_VERSIONS),
                Jsonl.topStr(p, SPRING_BOOT_BOM),
                Jsonl.strArray(p, IMPORTED_BOMS),
                tasks,
                relocations,
                xjc(Jsonl.nested(p, XJC)),
                avro(Jsonl.nested(p, AVRO)),
                resolved(Jsonl.strArray(p, RESOLVED)),
                compilerArgs == null || !Jsonl.has(compilerArgs, COMPILE_JAVA)
                        ? null
                        : Jsonl.strArray(compilerArgs, COMPILE_JAVA),
                compilerArgs == null || !Jsonl.has(compilerArgs, COMPILE_TEST_JAVA)
                        ? null
                        : Jsonl.strArray(compilerArgs, COMPILE_TEST_JAVA),
                testJson == null
                        ? null
                        : new TestTask(Jsonl.strMap(testJson, SYSTEM_PROPERTIES), Jsonl.strArray(testJson, JVM_ARGS)),
                Jsonl.topStr(p, ARCHIVES_NAME),
                publications,
                ruled(Jsonl.nested(p, RULED)),
                producers(Jsonl.nested(p, PRODUCERS)));
    }

    private static Map<String, List<Producer>> producers(@Nullable String json) {
        if (json == null) return Map.of();
        Map<String, List<Producer>> out = new LinkedHashMap<>();
        if (MiniJson.parse(json) instanceof Map<?, ?> byDir) {
            byDir.forEach((dir, rows) -> {
                List<Producer> list = new ArrayList<>();
                if (rows instanceof List<?> l) {
                    for (Object row : l) {
                        if (row instanceof Map<?, ?> r && r.get("task") != null) {
                            Object plugin = r.get("plugin");
                            list.add(new Producer(
                                    String.valueOf(r.get("task")),
                                    String.valueOf(r.get("type")),
                                    plugin == null ? null : String.valueOf(plugin)));
                        }
                    }
                }
                out.put(String.valueOf(dir), List.copyOf(list));
            });
        }
        return out;
    }

    private static @Nullable Xjc xjc(@Nullable String json) {
        if (json == null) return null;
        return new Xjc(
                Jsonl.topStr(json, "xsdDir"),
                Jsonl.topStr(json, "defaultPackage"),
                Jsonl.strArray(json, "bindingFiles"),
                Jsonl.strArray(json, "options"));
    }

    private static @Nullable Avro avro(@Nullable String json) {
        if (json == null) return null;
        return new Avro(Jsonl.strArray(json, AVRO_SOURCES), Jsonl.topStr(json, AVRO_BUILD_DIR));
    }

    /** Per plugin id, each read property's value as the init script wrote it. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> ruled(@Nullable String json) {
        if (json == null) return Map.of();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (MiniJson.parse(json) instanceof Map<?, ?> byId) {
            byId.forEach((id, values) -> {
                if (values instanceof Map<?, ?> m) out.put(String.valueOf(id), (Map<String, Object>) m);
            });
        }
        return out;
    }

    /** {@code group:artifact@version} rows as each module's highest version. */
    private static Map<String, String> resolved(List<String> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String row : rows) {
            int at = row.lastIndexOf('@');
            if (at <= 0 || at == row.length() - 1) continue;
            out.merge(row.substring(0, at), row.substring(at + 1), (a, b) -> Versions.compare(a, b) >= 0 ? a : b);
        }
        return out;
    }

    private static Dependency parseDependency(String d) {
        return new Dependency(
                str(d, "kind"),
                str(d, "group"),
                str(d, "artifact"),
                str(d, "version"),
                Jsonl.topStr(d, STRICT),
                Jsonl.topStr(d, REQUIRED),
                Jsonl.topStr(d, PREFER),
                Jsonl.topStr(d, CATEGORY),
                Jsonl.bool(d, INTRANSITIVE, false),
                Jsonl.strArray(d, EXCLUDES),
                Jsonl.topStr(d, CLASSIFIER),
                Jsonl.topStr(d, TYPE),
                Jsonl.topStr(d, PROJECT_PATH),
                Jsonl.bool(d, FIXTURES, false),
                Jsonl.strArray(d, FILES),
                Jsonl.topStr(d, TEXT));
    }

    private static String str(String json, String key) {
        String v = Jsonl.topStr(json, key);
        return v == null ? "" : v;
    }

    /** {@code "17"} or {@code "1.8"} as a major; {@code 0} for none. */
    static int major(@Nullable String compatibility) {
        if (compatibility == null || compatibility.isBlank()) return 0;
        String v = compatibility.startsWith("1.") ? compatibility.substring(2) : compatibility;
        int dot = v.indexOf('.');
        try {
            return Integer.parseInt(dot > 0 ? v.substring(0, dot) : v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
