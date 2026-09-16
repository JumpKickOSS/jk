// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.TestJvm;
import java.util.ArrayList;
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
 * [test] include-tags} / {@code exclude-tags}, its {@code <argLine>} is {@code [test] jvm-args} and
 * its system properties are {@code [test] system-properties}; its class patterns and a skip flag
 * have no {@code [test]} key and are rows saying where each lands. Failsafe defines jk's {@code
 * integration} suite, which is a directory, so its patterns are a row telling the user which
 * classes to move; its JVM settings land in the same keys when Surefire set none. JaCoCo is
 * {@code jk test --coverage}, a run flag.
 */
final class TestPlugins {

    /** The {@code [test]} tag filters and test JVM settings a POM's test plugins declare. */
    record TestSettings(List<String> includeTags, List<String> excludeTags, TestJvm jvm) {
        static final TestSettings NONE = new TestSettings(List.of(), List.of(), TestJvm.EMPTY);
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
        PluginFacts.plugin(model, FAILSAFE).ifPresent(failsafe -> mapFailsafe(failsafe, model, report, jvm));
        if (PluginFacts.plugin(model, "jacoco-maven-plugin").isPresent()) {
            report.warning("`jacoco-maven-plugin` — coverage is a run flag in jk, not a build setting:"
                    + " `jk test --coverage` runs every suite JVM under the JaCoCo agent and writes"
                    + " `reports/jacoco.xml` per module.");
        }
        return new TestSettings(tags.includeTags(), tags.excludeTags(), jvm.toTestJvm());
    }

    private static TestSettings mapSurefire(Plugin surefire, Model model, ImportReport.Builder report, Jvm jvm) {
        Set<String> include = new LinkedHashSet<>();
        Set<String> exclude = new LinkedHashSet<>();
        for (Xpp3Dom config : PluginFacts.configurations(surefire)) {
            tags(config, "groups", SUREFIRE, report).ifPresent(include::addAll);
            tags(config, "excludedGroups", SUREFIRE, report).ifPresent(exclude::addAll);
            reportPatterns(config, SUREFIRE, report);
        }
        jvm.take(surefire, SUREFIRE, model, report);
        reportSkip(surefire, model, report);
        return new TestSettings(List.copyOf(include), List.copyOf(exclude), TestJvm.EMPTY);
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

    /** Class patterns have no key: jk runs every class of the suite directory and selects with {@code --class}. */
    private static void reportPatterns(Xpp3Dom config, String plugin, ImportReport.Builder report) {
        for (String element : new String[] {"includes", "excludes"}) {
            List<String> patterns = children(config.getChild(element));
            if (patterns.isEmpty()) continue;
            report.warning("`" + plugin + "` `<" + element + ">` " + String.join(", ", patterns)
                    + " — jk runs every test class in the suite directory; select classes at run time with"
                    + " `jk test --class '<pattern>'`, or tag them and filter with `[test] include-tags` /"
                    + " `exclude-tags`.");
        }
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
     * Failsafe's second test pass is jk's {@code integration} suite, and a suite is a directory: the
     * row names the classes to move there. Its JVM settings land in the same keys as Surefire's.
     */
    private static void mapFailsafe(Plugin failsafe, Model model, ImportReport.Builder report, Jvm jvm) {
        Set<String> includes = new LinkedHashSet<>();
        for (Xpp3Dom config : PluginFacts.configurations(failsafe)) {
            includes.addAll(children(config.getChild("includes")));
            for (String element : new String[] {"groups", "excludedGroups"}) {
                String value = PluginFacts.child(config, element);
                if (value != null) {
                    report.warning("`maven-failsafe-plugin` `<" + element + ">" + value + "</" + element
                            + ">` — jk's tag filters apply to every suite, not to `integration` alone; tag the"
                            + " integration classes and select with `jk test --suite integration --include-tags`.");
                }
            }
        }
        List<String> patterns = includes.isEmpty() ? FAILSAFE_DEFAULT_INCLUDES : List.copyOf(includes);
        report.warning("`maven-failsafe-plugin` runs " + String.join(", ", patterns)
                + " as the integration pass; jk's `integration` suite is the directory `src/integration/java`."
                + " Move those classes there — `jk test --suite integration` (or `--guard`) runs them, and"
                + " `jk test` alone does not.");
        jvm.take(failsafe, FAILSAFE, model, report);
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
