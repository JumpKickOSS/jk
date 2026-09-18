// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
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
record GradleModel(String gradleVersion, String rootName, List<String> settingsRepositories, List<Project> projects) {

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
            @Nullable String mainClass,
            boolean bootBuildInfo,
            Map<String, String> manifest,
            List<String> repositories,
            List<SourceSet> sourceSets,
            List<Configuration> configurations,
            Map<String, String> managedVersions,
            @Nullable String springBootBom,
            List<String> importedBoms,
            List<Task> tasks) {

        boolean isRoot() {
            return dir.isEmpty();
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
    record Java(int toolchain, int source, int target, int release) {}

    record SourceSet(String name, List<String> java, List<String> resources, List<String> kotlin) {}

    record Configuration(String name, List<Dependency> dependencies, List<Constraint> constraints) {}

    /**
     * One declared dependency. {@code kind} is {@code module} (Maven coordinates), {@code project}
     * (a sibling, {@code projectPath}), {@code files} or {@code other}. {@code version} is the
     * declared version, empty when the declaration carried none; {@code strict}/{@code
     * required}/{@code prefer} are the rich-version parts; {@code category} is {@code platform} or
     * {@code enforced-platform} for a BOM.
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
    static final String GRADLE = "gradle";
    static final String IMPORTED_BOMS = "importedBoms";
    static final String INTRANSITIVE = "intransitive";
    static final String JAVA = "java";
    static final String KOTLIN = "kotlin";
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
    static final String RELEASE = "release";
    static final String REPOSITORIES = "repositories";
    static final String REQUIRED = "required";
    static final String RESOURCES = "resources";
    static final String ROOT_NAME = "rootName";
    static final String SETTINGS_REPOSITORIES = "settingsRepositories";
    static final String SOURCE_COMPATIBILITY = "sourceCompatibility";
    static final String SOURCE_SETS = "sourceSets";
    static final String SPRING_BOOT_BOM = "springBootBom";
    static final String STRICT = "strict";
    static final String TARGET_COMPATIBILITY = "targetCompatibility";
    static final String TASKS = "tasks";
    static final String TEXT = "text";
    static final String TOOLCHAIN = "toolchain";
    static final String TYPE = "type";

    /** Parse the JSON the init script wrote. */
    static GradleModel parse(String json) {
        List<Project> projects = new ArrayList<>();
        for (String p : Jsonl.objectArray(json, PROJECTS)) projects.add(parseProject(p));
        return new GradleModel(
                Jsonl.topStr(json, GRADLE) == null ? "" : Jsonl.requiredStr(json, GRADLE),
                Jsonl.topStr(json, ROOT_NAME) == null ? "" : Jsonl.requiredStr(json, ROOT_NAME),
                Jsonl.strArray(json, SETTINGS_REPOSITORIES),
                projects);
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
        String javaJson = Jsonl.nested(p, JAVA);
        Java java = javaJson == null
                ? null
                : new Java(
                        Jsonl.intValue(javaJson, TOOLCHAIN, 0),
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
                Jsonl.topStr(p, MAIN_CLASS),
                Jsonl.bool(p, BOOT_BUILD_INFO, false),
                Jsonl.strMap(p, MANIFEST),
                Jsonl.strArray(p, REPOSITORIES),
                sourceSets,
                configurations,
                Jsonl.strMap(p, MANAGED_VERSIONS),
                Jsonl.topStr(p, SPRING_BOOT_BOM),
                Jsonl.strArray(p, IMPORTED_BOMS),
                tasks);
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
