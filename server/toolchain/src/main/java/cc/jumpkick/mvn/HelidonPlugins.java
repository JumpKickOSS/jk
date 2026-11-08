// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Jlink;
import cc.jumpkick.model.LibDir;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.TestJvm;
import cc.jumpkick.repo.Pom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * A Helidon 27+ application is the {@code [helidon]} table, which brings what Helidon's application
 * parent and the archetype's POM spell out: the {@code helidon-dependencies} BOM, the annotation
 * processors, the test JVM's config profile and the {@code <name>.jar} + {@code libs/} deployable.
 * The version is the {@code io.helidon.applications:helidon-se} parent's (or {@code
 * helidon-applications}'), else the {@code io.helidon:helidon-dependencies} BOM the POM imports;
 * neither is a {@code [platform-dependencies]} row, since the table contributes that BOM.
 *
 * <ul>
 *   <li>{@code helidon-service-maven-plugin}'s {@code create-application} and its test variant are
 *       the table's {@code binding} (written {@code false} only when {@code <generateBinding>} is),
 *       {@code generate-main} and {@code validate}; options the table has no key for are rows.
 *   <li>Helidon's processor paths ({@code helidon-codegen-apt}, {@code helidon-service-codegen})
 *       are dropped, since the table contributes them; {@code helidon-bundles-apt} or one of the
 *       code generators it bundles (Declarative, builder, data, JSON) is {@code declarative = true}.
 *   <li>The parent's {@code copy-libs} execution is the table's default {@code layout = "libs"},
 *       so no {@code [application] lib-dir}; a module that copies nothing is {@code layout = "jar"},
 *       and a copy shaped otherwise keeps its {@code lib-dir}, which wins over the table's.
 *   <li>{@code helidon-maven-plugin}'s {@code jlink-image} goal is {@code [jlink]}.
 *   <li>Surefire's {@code helidon.config.profile=test}, the parent's own, is dropped: the table
 *       sets it.
 * </ul>
 *
 * <p>Below 27 the plugin refuses the table, so an older Helidon is imported as any other parent,
 * with a row saying why.
 */
final class HelidonPlugins {

    static final String SERVICE_PLUGIN = "helidon-service-maven-plugin";
    static final String MAVEN_PLUGIN = "helidon-maven-plugin";

    /** The lowest Helidon release the {@code [helidon]} table accepts. */
    static final int MIN_MAJOR = 27;

    /** Parents whose management is exactly {@code helidon-dependencies}', so the table replaces them. */
    private static final Set<String> PARENTS = Set.of(
            "io.helidon.applications:helidon-se",
            "io.helidon.applications:helidon-applications",
            "io.helidon:helidon-dependencies");

    private static final String BOM = "io.helidon:helidon-dependencies";

    /** Processor paths the table contributes for every Helidon module (with the service registry on the classpath). */
    private static final Set<String> BASE_PROCESSORS =
            Set.of("io.helidon.codegen:helidon-codegen-apt", "io.helidon.service:helidon-service-codegen");

    /** {@code helidon-bundles-apt} and the code generators it bundles: {@code declarative = true} carries each. */
    private static final Set<String> DECLARATIVE_PROCESSORS = Set.of(
            "io.helidon.bundles:helidon-bundles-apt",
            "io.helidon.declarative:helidon-declarative-codegen",
            "io.helidon.builder:helidon-builder-codegen",
            "io.helidon.data.codegen:helidon-data-codegen",
            "io.helidon.json:helidon-json-codegen",
            "io.helidon.json.schema:helidon-json-schema-codegen",
            "io.helidon.codegen:helidon-codegen-api-stability");

    private static final Set<String> BINDING_GOALS =
            Set.of("create-application", "create-test-application", "test-application-create");
    private static final String JLINK_GOAL = "jlink-image";
    /** The property {@code create-application}'s {@code <failOnWarning>} defaults to. */
    private static final String FAIL_ON_WARNING_PROPERTY = "helidon.service.registry.fail-on-warning";

    /** {@code create-application} options the table models, or that change nothing jk does. */
    private static final Set<String> COVERED_SERVICE_OPTIONS = Set.of(
            "generateBinding",
            "generateMain",
            "validate",
            "failOnWarning",
            "release",
            "outputDirectory",
            "testOutputDirectory",
            "generatedSourcesDirectory",
            "generatedTestSourcesDirectory",
            "failOnError");

    private static final String TEST_PROFILE = "helidon.config.profile";

    /** The parent's {@code copy-libs} leaves okio out (square/okio#1306); the layout needs no rule for it. */
    private static final Set<String> PARENT_LIB_EXCLUDES = Set.of("okio");

    /**
     * The import's Helidon decisions: the table, the platform rows it replaces, the plugins it
     * accounts for and the {@code [jlink]} table the {@code jlink-image} goal is.
     */
    record Helidon(
            PluginConfig table,
            Set<String> platforms,
            Set<String> consumedPlugins,
            boolean libDirCovered,
            @Nullable Jlink jlink) {

        /** Whether the {@code [platform-dependencies]} entry for {@code module} ({@code group:artifact}) is the table's. */
        boolean replacesPlatform(String module) {
            return platforms.contains(module);
        }

        /** {@code paths} less the processors the table contributes. */
        PluginFacts.ProcessorPaths processors(PluginFacts.ProcessorPaths paths) {
            return new PluginFacts.ProcessorPaths(
                    withoutContributed(paths.shared()), withoutContributed(paths.test()), paths.testOwn());
        }

        /** {@code tests} less the test config profile the table sets, when Helidon's value is the one written. */
        TestPlugins.TestSettings tests(TestPlugins.TestSettings tests) {
            TestJvm jvm = tests.jvm();
            if (!"test".equals(jvm.systemProperties().get(TEST_PROFILE))) return tests;
            Map<String, String> properties = new LinkedHashMap<>(jvm.systemProperties());
            properties.remove(TEST_PROFILE);
            return new TestPlugins.TestSettings(
                    tests.includeTags(),
                    tests.excludeTags(),
                    tests.excludeClasses(),
                    tests.excludeDependencies(),
                    tests.integration(),
                    new TestJvm(jvm.jvmArgs(), properties),
                    tests.failures());
        }

        /** {@code libDir}, or {@code null} when it is the layout the table lays out by default. */
        @Nullable
        LibDir libDir(@Nullable LibDir libDir) {
            return libDirCovered ? null : libDir;
        }
    }

    private HelidonPlugins() {}

    /**
     * The module's Helidon table, or {@code null} for a module with no Helidon parent or BOM, or
     * one below 27 (a row says so). {@code libDir} is the layout the dependency plugin's copy maps
     * to and {@code mainClass} the application main, which the layout belongs to.
     */
    static @Nullable Helidon map(
            EffectiveModel em, @Nullable LibDir libDir, @Nullable String mainClass, ImportReport.Builder report) {
        Set<String> platforms = new LinkedHashSet<>();
        String version = null;
        for (EffectiveModel.Ancestor ancestor : em.ancestors()) {
            String ga = ancestor.groupId() + ":" + ancestor.artifactId();
            if (PARENTS.contains(ga) && ancestor.published()) {
                version = ancestor.version();
                platforms.add(ga);
                break;
            }
        }
        for (Pom.Dep bom : em.management(Set.of()).platform()) {
            if (!BOM.equals(bom.module())) continue;
            String bomVersion = PluginFacts.usable(bom.version());
            if (bomVersion == null) continue;
            if (version == null) version = bomVersion;
            platforms.add(BOM);
        }
        if (version == null) return null;
        if (PackagingPlugins.majorOf(version) < MIN_MAJOR) {
            report.warning("Helidon " + version + ": the [helidon] table is for Helidon " + MIN_MAJOR
                    + " and later, so this module is imported without it, its Helidon parent or BOM as a"
                    + " [platform-dependencies] row and Helidon's Maven plugins as rows. Upgrade, then add"
                    + " [helidon] version");
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("version", version);
        Set<String> consumed = new LinkedHashSet<>();
        mapBinding(em, values, consumed, report);
        boolean libDirCovered = false;
        if (mainClass != null) {
            // No copy into target/ runs under Maven, so the deployable is the plain jar.
            if (libDir == null) values.put("layout", "jar");
            else libDirCovered = isParentLayout(libDir);
        }
        if (declarative(PluginFacts.annotationProcessorPaths(em.model()))) values.put("declarative", true);
        Jlink jlink = mainClass == null ? null : mapJlink(em, consumed, report);
        return new Helidon(new PluginConfig("helidon", values), platforms, consumed, libDirCovered, jlink);
    }

    /** Helidon's own copy: {@code target/libs}, versioned names, at most the parent's okio exclusion. */
    private static boolean isParentLayout(LibDir libDir) {
        return LibDir.DEFAULT_DIR.equals(libDir.dir())
                && libDir.versions()
                && PARENT_LIB_EXCLUDES.containsAll(libDir.exclude());
    }

    private static List<Pom.Dep> withoutContributed(List<Pom.Dep> paths) {
        List<Pom.Dep> out = new ArrayList<>();
        for (Pom.Dep path : paths) {
            if (!BASE_PROCESSORS.contains(path.module()) && !DECLARATIVE_PROCESSORS.contains(path.module())) {
                out.add(path);
            }
        }
        return out;
    }

    private static boolean declarative(PluginFacts.ProcessorPaths paths) {
        return paths.all().stream().anyMatch(p -> DECLARATIVE_PROCESSORS.contains(p.module()));
    }

    /**
     * {@code create-application} / its test variant → {@code binding}, {@code generate-main},
     * {@code validate}, and {@code <failOnWarning>} (or the {@code
     * helidon.service.registry.fail-on-warning} property it defaults to) → {@code fail-on-warning}. The table's {@code binding} default, on when a class carries the {@code
     * Service.GenerateBinding} annotation (the only thing that loads the generated binding), is
     * what the goal does, so only {@code <generateBinding>false</generateBinding>} is written.
     */
    private static void mapBinding(
            EffectiveModel em, Map<String, Object> values, Set<String> consumed, ImportReport.Builder report) {
        Optional<Plugin> plugin = PluginFacts.plugin(em.model(), SERVICE_PLUGIN);
        if (plugin.isEmpty()) return;
        List<PluginExecution> executions = plugin.get().getExecutions().stream()
                .filter(e -> e.getGoals().stream().anyMatch(BINDING_GOALS::contains))
                .toList();
        if (executions.isEmpty()) return;
        if (plugin.get().getExecutions().stream().allMatch(e -> BINDING_GOALS.containsAll(e.getGoals())))
            consumed.add(SERVICE_PLUGIN);
        boolean binding = true;
        boolean generateMain = false;
        boolean validate = true;
        boolean warningsFail =
                Boolean.parseBoolean(String.valueOf(em.model().getProperties().getProperty(FAIL_ON_WARNING_PROPERTY))
                        .trim());
        boolean failOnWarning = false;
        Set<String> unmapped = new LinkedHashSet<>();
        for (PluginExecution execution : executions) {
            Xpp3Dom config = configuration(plugin.get(), execution);
            if (config == null) {
                failOnWarning |= warningsFail;
                continue;
            }
            binding &= flag(config, "generateBinding", true);
            generateMain |= flag(config, "generateMain", false);
            validate &= flag(config, "validate", true);
            failOnWarning |= flag(config, "failOnWarning", warningsFail);
            for (Xpp3Dom option : config.getChildren()) {
                if (!COVERED_SERVICE_OPTIONS.contains(option.getName())) unmapped.add(option.getName());
            }
        }
        if (!binding) values.put("binding", false);
        if (generateMain) values.put("generate-main", true);
        if (!validate) values.put("validate", false);
        if (failOnWarning) values.put("fail-on-warning", true);
        if (!unmapped.isEmpty()) {
            report.warning(SERVICE_PLUGIN + " <" + String.join(">, <", unmapped) + ">: not carried into [helidon],"
                    + " which generates the binding under Helidon's default names and options");
        }
    }

    /**
     * {@code helidon-maven-plugin:jlink-image} → {@code [jlink]}: {@code <additionalModules>} →
     * {@code modules}, {@code <stripDebug>} → {@code strip-debug} (Helidon's default keeps debug
     * attributes), {@code <aotCache>} and {@code <addClassDataSharingArchive>} → {@code aot-cache},
     * {@code <defaultJvmOptions>} → {@code jvm-args}, {@code <defaultArgs>} → {@code args},
     * {@code <maxAppStartSeconds>} → {@code start-timeout}. A skipped goal is no table.
     */
    private static @Nullable Jlink mapJlink(EffectiveModel em, Set<String> consumed, ImportReport.Builder report) {
        Optional<Plugin> plugin = PluginFacts.plugin(em.model(), MAVEN_PLUGIN);
        if (plugin.isEmpty()) return null;
        List<PluginExecution> executions = plugin.get().getExecutions().stream()
                .filter(e -> e.getGoals().contains(JLINK_GOAL))
                .toList();
        if (executions.isEmpty()) return null;
        if (plugin.get().getExecutions().stream()
                .allMatch(e -> List.of(JLINK_GOAL).containsAll(e.getGoals()))) consumed.add(MAVEN_PLUGIN);
        Xpp3Dom config = configuration(plugin.get(), executions.getFirst());
        if (flag(config, "skipJavaImage", false)) {
            report.warning(MAVEN_PLUGIN + " jlink-image is skipped (<skipJavaImage>true</skipJavaImage>); no [jlink]");
            return null;
        }
        List<String> modules = values(config, "additionalModules");
        boolean stripDebug = flag(config, "stripDebug", false);
        String aot = PluginFacts.child(config, "aotCache");
        boolean cds = flag(config, "addClassDataSharingArchive", true);
        Jlink.AotCache cache =
                switch (aot == null ? "true" : aot) {
                    case "cds" -> Jlink.AotCache.CDS;
                    case "false" -> cds ? Jlink.AotCache.CDS : Jlink.AotCache.OFF;
                    default -> Jlink.AotCache.AUTO;
                };
        int timeout = Jlink.DEFAULT_START_TIMEOUT;
        String seconds = PluginFacts.child(config, "maxAppStartSeconds");
        if (seconds != null) {
            try {
                timeout = Integer.parseInt(seconds);
            } catch (NumberFormatException e) {
                report.warning(MAVEN_PLUGIN + " <maxAppStartSeconds>" + seconds + "</maxAppStartSeconds> is not a"
                        + " number; [jlink] start-timeout keeps its default");
            }
        }
        boolean testStart = true;
        if (!flag(config, "testImage", true)) {
            if (cache == Jlink.AotCache.OFF) {
                testStart = false;
            } else {
                report.warning(MAVEN_PLUGIN + " <testImage>false</testImage>: [jlink] trains its startup cache by"
                        + " starting the image, so the build still starts it once; aot-cache = \"off\" with"
                        + " test-start = false skips both");
            }
        }
        for (String named : List.of("additionalJlinkArgs", "defaultDebugOptions", "finalName")) {
            if (config != null && config.getChild(named) != null) {
                report.warning(MAVEN_PLUGIN + " <" + named + "> not imported: [jlink] writes target/<name>-jri/ with"
                        + " jdeps' modules, and bin/start --debug uses jk's debug options");
            }
        }
        return new Jlink(
                modules,
                stripDebug,
                cache,
                values(config, "defaultJvmOptions"),
                values(config, "defaultArgs"),
                timeout,
                testStart,
                Map.of());
    }

    /** The values of a list (or comma-separated) parameter. */
    private static List<String> values(@Nullable Xpp3Dom config, String name) {
        Xpp3Dom node = config == null ? null : config.getChild(name);
        if (node == null) return List.of();
        List<String> out = new ArrayList<>();
        if (node.getChildCount() > 0) {
            for (Xpp3Dom child : node.getChildren()) {
                String value = PluginFacts.usable(child.getValue());
                if (value != null && !out.contains(value)) out.add(value);
            }
        } else {
            String text = PluginFacts.usable(node.getValue());
            if (text != null) {
                for (String part : text.split(",")) {
                    if (!part.isBlank() && !out.contains(part.trim())) out.add(part.trim());
                }
            }
        }
        return out;
    }

    /** The configuration Maven runs {@code execution} with: the plugin's, overlaid by the execution's. */
    private static @Nullable Xpp3Dom configuration(Plugin plugin, PluginExecution execution) {
        Xpp3Dom own = execution.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
        Xpp3Dom shared = plugin.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
        if (own == null) return shared;
        if (shared == null) return own;
        return Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(own), new Xpp3Dom(shared));
    }

    /**
     * A Maven boolean parameter as Maven reads it: absent is the parameter's default, and a present
     * value is {@link Boolean#parseBoolean} (only {@code true}, any case, is true).
     */
    private static boolean flag(@Nullable Xpp3Dom config, String name, boolean absent) {
        String raw = PluginFacts.child(config, name);
        return raw == null ? absent : Boolean.parseBoolean(raw.trim());
    }
}
