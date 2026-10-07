// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.ClassSuite;
import cc.jumpkick.model.TestFailureMode;
import cc.jumpkick.model.TestJvm;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * The plugins that run tests. Surefire's {@code <groups>} / {@code <excludedGroups>} are {@code
 * [test] include-tags} / {@code exclude-tags}, its {@code <excludes>} are {@code [test]
 * exclude-classes}, its {@code <classpathDependencyExcludes>} are {@code [test] exclude-dependencies},
 * its {@code <argLine>} is {@code [test] jvm-args} and its system properties are {@code [test]
 * system-properties}; its {@code <includes>} and a skip flag have no {@code [test]}
 * key and are rows saying where each lands. Failsafe's {@code <includes>} (or its defaults) are the
 * class patterns of jk's {@code integration} suite, {@code [test.suites.integration] classes}, so
 * plain {@code jk test} leaves those classes out as {@code mvn test} does, and its {@code
 * <excludes>} are that suite's {@code exclude-classes}; its JVM settings land in the same keys when
 * Surefire set none. JaCoCo is {@code jk test --coverage}, a run flag.
 */
final class TestPlugins {

    /**
     * The {@code [test]} tag filters, excluded classes and dependencies, the {@code integration}
     * suite (null without Failsafe), and test JVM settings a POM's test plugins declare.
     */
    record TestSettings(
            List<String> includeTags,
            List<String> excludeTags,
            List<String> excludeClasses,
            List<String> excludeDependencies,
            @Nullable ClassSuite integration,
            TestJvm jvm,
            TestFailureMode failures) {
        static final TestSettings NONE =
                new TestSettings(List.of(), List.of(), List.of(), List.of(), null, TestJvm.EMPTY, TestFailureMode.FAIL);
    }

    private static final String SUREFIRE = "maven-surefire-plugin";
    private static final String FAILSAFE = "maven-failsafe-plugin";
    private static final List<String> FAILSAFE_DEFAULT_INCLUDES =
            List.of("**/IT*.java", "**/*IT.java", "**/*ITCase.java");
    /** JUnit tag-expression operators; a value carrying one is not a plain tag list. */
    private static final String TAG_EXPRESSION_OPERATORS = "&!()";

    private TestPlugins() {}

    static TestSettings map(Model model, ImportReport.Builder report) {
        Jvm jvm = new Jvm();
        TestSettings tags = PluginFacts.plugin(model, SUREFIRE)
                .map(surefire -> mapSurefire(surefire, model, report, jvm))
                .orElse(TestSettings.NONE);
        ClassSuite integration = PluginFacts.plugin(model, FAILSAFE)
                .map(failsafe -> mapFailsafe(failsafe, model, report, jvm))
                .orElseGet(() -> unboundIntegration(model, report));
        if (PluginFacts.plugin(model, "jacoco-maven-plugin").isPresent()) {
            report.warning("`jacoco-maven-plugin` — coverage is a run flag in jk, not a build setting:"
                    + " `jk test --coverage` runs every suite JVM under the JaCoCo agent and writes"
                    + " `reports/jacoco.xml` per module.");
        }
        return new TestSettings(
                tags.includeTags(),
                tags.excludeTags(),
                tags.excludeClasses(),
                tags.excludeDependencies(),
                integration,
                jvm.toTestJvm(),
                failures(model, report));
    }

    /**
     * {@code report} when Maven would keep going past a failing test: {@code maven.test.failure.ignore}
     * set to true, or Surefire's or Failsafe's {@code <testFailureIgnore>}. jk's mode covers every
     * suite of the module, so a Failsafe-only setting is noted as widening to the unit tests.
     */
    static TestFailureMode failures(Model model, ImportReport.Builder report) {
        boolean property = Boolean.parseBoolean(model.getProperties().getProperty("maven.test.failure.ignore"));
        boolean surefire = ignoresFailures(model, SUREFIRE);
        boolean failsafe = ignoresFailures(model, FAILSAFE);
        if (!property && !surefire && !failsafe) return TestFailureMode.FAIL;
        if (failsafe && !property && !surefire) {
            report.warning("`maven-failsafe-plugin` `<testFailureIgnore>` is written as `[test] failures = \"report\"`,"
                    + " which covers every suite of the module, unit tests included.");
        }
        return TestFailureMode.REPORT;
    }

    private static boolean ignoresFailures(Model model, String plugin) {
        return PluginFacts.plugin(model, plugin)
                .map(p -> PluginFacts.configurations(p).stream()
                        .anyMatch(config -> Boolean.parseBoolean(PluginFacts.child(config, "testFailureIgnore"))))
                .orElse(false);
    }

    private static TestSettings mapSurefire(Plugin surefire, Model model, ImportReport.Builder report, Jvm jvm) {
        Set<String> include = new LinkedHashSet<>();
        Set<String> exclude = new LinkedHashSet<>();
        Set<String> excludeClasses = new LinkedHashSet<>();
        Set<String> excludeDependencies = new LinkedHashSet<>();
        for (Xpp3Dom config : PluginFacts.configurations(surefire)) {
            tags(config, "groups", SUREFIRE, report).ifPresent(include::addAll);
            tags(config, "excludedGroups", SUREFIRE, report).ifPresent(exclude::addAll);
            reportIncludes(config, report);
            excludeClasses.addAll(excludes(config, report));
            excludeDependencies.addAll(classpathDependencyExcludes(config, report));
        }
        jvm.take(surefire, SUREFIRE, model, report);
        reportSkip(surefire, model, report);
        return new TestSettings(
                List.copyOf(include),
                List.copyOf(exclude),
                List.copyOf(excludeClasses),
                List.copyOf(excludeDependencies),
                null,
                TestJvm.EMPTY,
                TestFailureMode.FAIL);
    }

    /**
     * A plain comma- or pipe-separated tag list, as {@code [test]} writes it; empty when the element
     * is absent, and a row instead when the value is a tag expression jk's list cannot spell.
     */
    private static Optional<List<String>> tags(
            Xpp3Dom config, String element, String plugin, ImportReport.Builder report) {
        String value = PluginFacts.child(config, element);
        if (value == null) return Optional.empty();
        if (value.chars().anyMatch(c -> TAG_EXPRESSION_OPERATORS.indexOf(c) >= 0)) {
            report.warning("`" + plugin + "` `<" + element + ">" + value + "</" + element
                    + ">` is a tag expression; `[test] include-tags` / `exclude-tags` list plain tags, so"
                    + " write the list yourself.");
            return Optional.empty();
        }
        List<String> tags = new ArrayList<>();
        for (String tag : value.split("[,|]")) {
            if (!tag.isBlank()) tags.add(tag.trim());
        }
        return Optional.of(tags);
    }

    /** Includes have no key: jk runs every class of the suite directory and selects with {@code --class}. */
    private static void reportIncludes(Xpp3Dom config, ImportReport.Builder report) {
        List<String> patterns = children(config.getChild("includes"));
        if (patterns.isEmpty()) return;
        report.warning("`" + SUREFIRE + "` `<includes>` " + String.join(", ", patterns)
                + " — jk runs every test class in the suite directory; select classes at run time with"
                + " `jk test --class '<pattern>'`, or tag them and filter with `[test] include-tags` /"
                + " `exclude-tags`.");
    }

    /**
     * {@code <excludes>} as {@code [test] exclude-classes} patterns; a pattern jk's class syntax
     * cannot spell ({@code %regex[…]}, {@code ?}, a method selector) and {@code <excludesFile>}
     * are a row.
     */
    private static List<String> excludes(Xpp3Dom config, ImportReport.Builder report) {
        List<String> out = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        for (String declared : children(config.getChild("excludes"))) {
            for (String pattern : declared.split(",")) {
                if (pattern.isBlank()) continue;
                String mapped = classPattern(pattern.trim());
                if (mapped == null) {
                    unmapped.add(pattern.trim());
                } else if (!out.contains(mapped)) {
                    out.add(mapped);
                }
            }
        }
        if (!unmapped.isEmpty()) {
            report.warning("`" + SUREFIRE + "` `<excludes>` " + String.join(", ", unmapped)
                    + " — `[test] exclude-classes` takes class names with `*` wildcards; add the classes by hand.");
        }
        String file = PluginFacts.child(config, "excludesFile");
        if (file != null) {
            report.warning("`" + SUREFIRE + "` `<excludesFile>" + file + "</excludesFile>` — list those classes"
                    + " under `[test] exclude-classes`.");
        }
        return out;
    }

    /**
     * {@code <classpathDependencyExcludes>} as {@code [test] exclude-dependencies} coordinates, from
     * child elements or a comma-separated value; an entry that is not {@code group:artifact} is a row.
     */
    private static List<String> classpathDependencyExcludes(Xpp3Dom config, ImportReport.Builder report) {
        Xpp3Dom element = config.getChild("classpathDependencyExcludes");
        if (element == null) return List.of();
        List<String> declared = children(element);
        String inline = PluginFacts.text(element);
        if (element.getChildCount() == 0 && inline != null) declared.add(inline);
        List<String> out = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        for (String value : declared) {
            for (String coordinate : value.split(",")) {
                String c = coordinate.trim();
                if (c.isEmpty()) continue;
                String[] parts = c.split(":", -1);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank() || c.contains("${")) {
                    unmapped.add(c);
                } else if (!out.contains(c)) {
                    out.add(c);
                }
            }
        }
        if (!unmapped.isEmpty()) {
            report.warning("`" + SUREFIRE + "` `<classpathDependencyExcludes>` " + String.join(", ", unmapped)
                    + " — `[test] exclude-dependencies` takes `group:artifact` coordinates; add them by hand.");
        }
        return out;
    }

    /**
     * A Surefire file pattern as a class pattern: {@code **}{@code /*PerformanceTest.java} is
     * {@code *PerformanceTest} (that simple name in any package), {@code org/acme/**}{@code /*IT}
     * is {@code org.acme.*IT}. Null when the pattern is a regex or uses a wildcard jk has not.
     */
    static @Nullable String classPattern(String pattern) {
        String p = pattern;
        if (p.startsWith("%ant[") && p.endsWith("]")) p = p.substring(5, p.length() - 1);
        if (p.startsWith("%regex[") || p.contains("?") || p.contains("[") || p.contains("{") || p.contains("#")) {
            return null;
        }
        if (p.endsWith(".java")) p = p.substring(0, p.length() - ".java".length());
        else if (p.endsWith(".class")) p = p.substring(0, p.length() - ".class".length());
        while (p.startsWith("**/")) p = p.substring(3);
        p = p.replace("**/", "*").replace("**", "*").replace('/', '.').replace('\\', '.');
        return p.isEmpty() ? null : p;
    }

    /**
     * The one {@code [test] jvm-args} / {@code system-properties} pair a module has. Surefire fills
     * it; Failsafe fills whichever half Surefire left empty, and what it cannot land is a row, since
     * jk runs every suite with the same test JVM settings.
     */
    private static final class Jvm {
        private final List<String> jvmArgs = new ArrayList<>();
        private final Map<String, String> properties = new LinkedHashMap<>();

        TestJvm toTestJvm() {
            return new TestJvm(jvmArgs, properties);
        }

        /**
         * {@code <argLine>} tokens minus {@code ${argLine}} / {@code @{argLine}} and the JaCoCo
         * agent (coverage is {@code jk test --coverage}); the {@code argLine} property counts when
         * the plugin declares none. Failsafe's values behind Surefire's are a row.
         */
        void take(Plugin plugin, String label, Model model, ImportReport.Builder report) {
            List<String> args = new ArrayList<>();
            Map<String, String> props = new LinkedHashMap<>();
            boolean configured = false;
            for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
                String argLine = PluginFacts.text(config.getChild("argLine"));
                if (argLine != null) {
                    configured = true;
                    args.addAll(jvmArgs(argLine));
                }
                props.putAll(systemProperties(config, label, report));
            }
            if (!configured) {
                String argLine = model.getProperties().getProperty("argLine");
                if (argLine != null) args.addAll(jvmArgs(argLine));
            }
            if (!args.isEmpty()) {
                if (jvmArgs.isEmpty()) {
                    jvmArgs.addAll(args);
                } else if (!jvmArgs.equals(args)) {
                    report.warning("`" + label + "` `<argLine>` " + String.join(" ", args)
                            + " — `[test] jvm-args` is one list for every suite and Surefire's "
                            + String.join(" ", jvmArgs) + " is in it; merge the two by hand.");
                }
            }
            if (!props.isEmpty()) {
                if (properties.isEmpty()) {
                    properties.putAll(props);
                } else if (!properties.equals(props)) {
                    report.warning("`" + label + "` system properties " + render(props)
                            + " — `[test] system-properties` is one table for every suite and Surefire's "
                            + render(properties) + " is in it; merge the two by hand.");
                }
            }
        }

        private static String render(Map<String, String> props) {
            List<String> out = new ArrayList<>();
            props.forEach((k, v) -> out.add("-D" + k + "=" + v));
            return String.join(" ", out);
        }
    }

    /** The argLine's tokens minus placeholders left uninterpolated and the JaCoCo agent. */
    private static List<String> jvmArgs(String argLine) {
        List<String> out = new ArrayList<>();
        for (String token : argLine.trim().split("\\s+")) {
            if (token.isEmpty() || token.contains("${") || token.contains("@{")) continue;
            if (token.startsWith("-javaagent:") && token.contains("jacoco")) continue;
            out.add(token);
        }
        return out;
    }

    /**
     * {@code <systemPropertyVariables><k>v</k>} and {@code <systemProperties><property>} as {@code
     * k -> v}. A value the effective model left uninterpolated ({@code ${…}} / {@code @{…}}) names a
     * build-time path or a plugin placeholder jk has no value for; it is a row, not a property.
     */
    private static Map<String, String> systemProperties(Xpp3Dom config, String label, ImportReport.Builder report) {
        Map<String, String> out = new LinkedHashMap<>();
        Xpp3Dom variables = config.getChild("systemPropertyVariables");
        if (variables != null) {
            for (Xpp3Dom variable : variables.getChildren()) {
                put(out, variable.getName(), PluginFacts.text(variable), label, report);
            }
        }
        Xpp3Dom properties = config.getChild("systemProperties");
        if (properties != null) {
            for (Xpp3Dom property : properties.getChildren()) {
                String name = PluginFacts.child(property, "name");
                if (name != null) put(out, name, PluginFacts.text(property.getChild("value")), label, report);
            }
        }
        return out;
    }

    private static void put(
            Map<String, String> out, String name, @Nullable String raw, String label, ImportReport.Builder report) {
        String value = raw == null ? "" : raw.trim();
        if (value.contains("${") || value.contains("@{")) {
            report.warning("`" + label + "` system property " + name + "=" + value
                    + " — the placeholder has no value outside Maven; set it under"
                    + " `[test] system-properties` yourself.");
            return;
        }
        out.put(name, value);
    }

    /** {@code <skipTests>} / {@code <skip>} in the configuration or as a property. */
    private static void reportSkip(Plugin surefire, Model model, ImportReport.Builder report) {
        boolean skip = false;
        for (Xpp3Dom config : PluginFacts.configurations(surefire)) {
            skip |= isTrue(PluginFacts.child(config, "skipTests")) || isTrue(PluginFacts.child(config, "skip"));
        }
        Properties props = model.getProperties();
        skip |= isTrue(props.getProperty("skipTests")) || isTrue(props.getProperty("maven.test.skip"));
        if (skip) {
            report.warning("`maven-surefire-plugin` is configured to skip tests; jk has no manifest key for that —"
                    + " `jk build --skip-tests` skips them for one build.");
        }
    }

    private static boolean isTrue(@Nullable String value) {
        return value != null && EnvValues.parseBool(value).orElse(false);
    }

    /**
     * Failsafe's second test pass is jk's {@code integration} suite: its {@code <includes>}, or
     * Failsafe's defaults, as class patterns over the test sources, less its {@code <excludes>}. A
     * pattern the class syntax cannot spell and the tag filters are rows. Its JVM settings land in
     * the same keys as Surefire's; null when no include maps.
     */
    private static @Nullable ClassSuite mapFailsafe(
            Plugin failsafe, Model model, ImportReport.Builder report, Jvm jvm) {
        Set<String> includes = new LinkedHashSet<>();
        Set<String> excludes = new LinkedHashSet<>();
        for (Xpp3Dom config : PluginFacts.configurations(failsafe)) {
            includes.addAll(children(config.getChild("includes")));
            excludes.addAll(children(config.getChild("excludes")));
            for (String element : new String[] {"groups", "excludedGroups"}) {
                String value = PluginFacts.child(config, element);
                if (value != null) {
                    report.warning("`maven-failsafe-plugin` `<" + element + ">" + value + "</" + element
                            + ">` — jk's tag filters apply to every suite, not to `integration` alone; tag the"
                            + " integration classes and select with `jk test --suite integration --include-tags`.");
                }
            }
        }
        List<String> declared = includes.isEmpty() ? FAILSAFE_DEFAULT_INCLUDES : List.copyOf(includes);
        List<String> patterns = classPatterns(declared, "<includes>", "classes", report);
        List<String> excluded = classPatterns(excludes, "<excludes>", "exclude-classes", report);
        jvm.take(failsafe, FAILSAFE, model, report);
        return patterns.isEmpty() ? null : new ClassSuite(patterns, excluded);
    }

    /**
     * Without a Failsafe in the build, a test class named the way Failsafe's defaults match
     * ({@code IT*}, {@code *IT}, {@code *ITCase}) never runs under {@code mvn test} or {@code mvn
     * verify}: Surefire's defaults leave it out, and only a profile's Failsafe would run it. Those
     * names are the {@code integration} suite, so plain {@code jk test} leaves them out too; null
     * when the test sources hold no such class.
     */
    private static @Nullable ClassSuite unboundIntegration(Model model, ImportReport.Builder report) {
        Path tests = testSourceDirectory(model);
        if (tests == null) return null;
        boolean found;
        try {
            found = PathUtil.anyRegularFile(
                    tests, d -> false, f -> integrationName(f.getFileName().toString()));
        } catch (IOException e) {
            return null;
        }
        if (!found) return null;
        report.warning("test classes named `IT*`, `*IT` or `*ITCase` run under no Failsafe in this build, so"
                + " `mvn test` leaves them out; they are jk's `integration` suite, which `jk test` leaves out"
                + " too — run them with `jk test --suite integration`.");
        return new ClassSuite(classPatterns(FAILSAFE_DEFAULT_INCLUDES, "<includes>", "classes", report), List.of());
    }

    /** Whether {@code fileName} is a source file Failsafe's default includes would select. */
    static boolean integrationName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) return false;
        String ext = fileName.substring(dot + 1);
        if (!ext.equals("java") && !ext.equals("kt") && !ext.equals("groovy")) return false;
        String simple = fileName.substring(0, dot);
        return simple.startsWith("IT") || simple.endsWith("IT") || simple.endsWith("ITCase");
    }

    private static @Nullable Path testSourceDirectory(Model model) {
        String declared = model.getBuild() == null ? null : model.getBuild().getTestSourceDirectory();
        File base = model.getProjectDirectory();
        if (declared != null && !declared.isBlank()) {
            Path p = Path.of(declared);
            if (!p.isAbsolute() && base != null) p = base.toPath().resolve(p);
            return Files.isDirectory(p) ? p : null;
        }
        if (base == null) return null;
        Path conventional = base.toPath().resolve("src/test/java");
        return Files.isDirectory(conventional) ? conventional : null;
    }

    /**
     * Failsafe's Ant-style {@code values} (comma lists allowed) as distinct class patterns; one the
     * class syntax cannot spell is a row naming the {@code [test.suites.integration]} key.
     */
    private static List<String> classPatterns(
            Collection<String> values, String element, String key, ImportReport.Builder report) {
        List<String> patterns = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        for (String value : values) {
            for (String pattern : value.split(",")) {
                if (pattern.isBlank()) continue;
                String mapped = classPattern(pattern.trim());
                if (mapped == null) unmapped.add(pattern.trim());
                else if (!patterns.contains(mapped)) patterns.add(mapped);
            }
        }
        if (!unmapped.isEmpty()) {
            report.warning("`maven-failsafe-plugin` `" + element + "` " + String.join(", ", unmapped)
                    + " — `[test.suites.integration] " + key + "` takes class names with `*` wildcards; add the"
                    + " classes by hand.");
        }
        return List.copyOf(patterns);
    }

    private static List<String> children(@Nullable Xpp3Dom list) {
        List<String> values = new ArrayList<>();
        if (list == null) return values;
        for (Xpp3Dom child : list.getChildren()) {
            String value = PluginFacts.text(child);
            if (value != null && !value.isBlank()) values.add(value.trim());
        }
        return values;
    }
}
