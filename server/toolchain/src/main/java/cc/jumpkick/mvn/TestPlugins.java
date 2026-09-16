// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * The plugins that run tests. Surefire's {@code <groups>} / {@code <excludedGroups>} are {@code
 * [test] include-tags} / {@code exclude-tags}; its class patterns, {@code <argLine>}, system
 * properties and a skip flag have no {@code [test]} key and are rows saying where each lands.
 * Failsafe defines jk's {@code integration} suite, which is a directory, so its patterns are a row
 * telling the user which classes to move. JaCoCo is {@code jk test --coverage}, a run flag.
 */
final class TestPlugins {

    /** The {@code [test]} tag filters a POM's test plugins declare. */
    record TestSettings(List<String> includeTags, List<String> excludeTags) {
        static final TestSettings NONE = new TestSettings(List.of(), List.of());
    }

    private static final String SUREFIRE = "maven-surefire-plugin";
    private static final String FAILSAFE = "maven-failsafe-plugin";
    private static final List<String> FAILSAFE_DEFAULT_INCLUDES =
            List.of("**/IT*.java", "**/*IT.java", "**/*ITCase.java");
    /** JUnit tag-expression operators; a value carrying one is not a plain tag list. */
    private static final String TAG_EXPRESSION_OPERATORS = "&!()";

    private TestPlugins() {}

    static TestSettings map(Model model, ImportReport.Builder report) {
        TestSettings settings = PluginFacts.plugin(model, SUREFIRE)
                .map(surefire -> mapSurefire(surefire, model, report))
                .orElse(TestSettings.NONE);
        PluginFacts.plugin(model, FAILSAFE).ifPresent(failsafe -> reportFailsafe(failsafe, model, report));
        if (PluginFacts.plugin(model, "jacoco-maven-plugin").isPresent()) {
            report.warning("`jacoco-maven-plugin` — coverage is a run flag in jk, not a build setting:"
                    + " `jk test --coverage` runs every suite JVM under the JaCoCo agent and writes"
                    + " `reports/jacoco.xml` per module.");
        }
        return settings;
    }

    private static TestSettings mapSurefire(Plugin surefire, Model model, ImportReport.Builder report) {
        Set<String> include = new LinkedHashSet<>();
        Set<String> exclude = new LinkedHashSet<>();
        for (Xpp3Dom config : PluginFacts.configurations(surefire)) {
            tags(config, "groups", SUREFIRE, report).ifPresent(include::addAll);
            tags(config, "excludedGroups", SUREFIRE, report).ifPresent(exclude::addAll);
            reportPatterns(config, SUREFIRE, report);
        }
        reportJvm(surefire, SUREFIRE, model, report);
        reportSkip(surefire, model, report);
        return new TestSettings(List.copyOf(include), List.copyOf(exclude));
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
     * {@code <argLine>} and system properties have no test-only key. The row names what was set;
     * {@code ${argLine}} / {@code @{argLine}} and the JaCoCo agent are dropped, since coverage is
     * {@code jk test --coverage}.
     */
    private static void reportJvm(Plugin plugin, String label, Model model, ImportReport.Builder report) {
        List<String> jvmArgs = new ArrayList<>();
        List<String> properties = new ArrayList<>();
        boolean configured = false;
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            String argLine = PluginFacts.text(config.getChild("argLine"));
            if (argLine != null) {
                configured = true;
                jvmArgs.addAll(jvmArgs(argLine));
            }
            properties.addAll(systemProperties(config));
        }
        if (!configured) {
            String argLine = model.getProperties().getProperty("argLine");
            if (argLine != null) jvmArgs.addAll(jvmArgs(argLine));
        }
        if (!jvmArgs.isEmpty()) {
            report.warning("`" + label + "` `<argLine>` " + String.join(" ", jvmArgs)
                    + " — jk has no test-only JVM flags. `[jvm] args` reaches every worker JVM the module"
                    + " forks, compilers included; a value the tests read belongs in `[test] env`.");
        }
        if (!properties.isEmpty()) {
            report.warning("`" + label + "` system properties " + String.join(" ", properties)
                    + " — jk has no `[test] system-properties`; hand the tests the values through"
                    + " `[test] env` and read them from the environment.");
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

    /** {@code <systemPropertyVariables><k>v</k>} and {@code <systemProperties><property>} as {@code -Dk=v}. */
    private static List<String> systemProperties(Xpp3Dom config) {
        List<String> out = new ArrayList<>();
        Xpp3Dom variables = config.getChild("systemPropertyVariables");
        if (variables != null) {
            for (Xpp3Dom variable : variables.getChildren()) {
                String value = PluginFacts.text(variable);
                out.add("-D" + variable.getName() + "=" + (value == null ? "" : value.trim()));
            }
        }
        Xpp3Dom properties = config.getChild("systemProperties");
        if (properties != null) {
            for (Xpp3Dom property : properties.getChildren()) {
                String name = PluginFacts.child(property, "name");
                if (name == null) continue;
                String value = PluginFacts.text(property.getChild("value"));
                out.add("-D" + name + "=" + (value == null ? "" : value.trim()));
            }
        }
        return out;
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
     * row names the classes to move there. Its JVM settings get the same rows as Surefire's.
     */
    private static void reportFailsafe(Plugin failsafe, Model model, ImportReport.Builder report) {
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
                + " `jk test` alone no longer does.");
        reportJvm(failsafe, FAILSAFE, model, report);
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
