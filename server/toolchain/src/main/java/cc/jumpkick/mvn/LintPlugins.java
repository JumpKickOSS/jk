// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * The lint plugins are one {@code [lint]} table. {@code maven-checkstyle-plugin}: {@code
 * <configLocation>} is {@code checkstyle} (a built-in {@code sun_checks.xml} / {@code
 * google_checks.xml} is a row, since the preset reads a file in the module), the Checkstyle
 * version the plugin's own {@code <dependencies>} pin is {@code checkstyle-version}, {@code
 * <includeTestSourceDirectory>} adds {@code src/test/java} to {@code sources}, a {@code
 * <violationSeverity>} of {@code warning} is {@code fail-on = "warning"}. {@code maven-pmd-plugin}:
 * {@code <rulesets>} are {@code pmd} — a {@code /category/…} or {@code /rulesets/…} path a built-in
 * one, a {@code file://} URL a module file, the Maven plugin's own default ruleset a row — and
 * {@code <includeTests>} adds the test root. {@code spotbugs-maven-plugin}: {@code spotbugs = true},
 * {@code <excludeFilterFile>} is {@code spotbugs-exclude}, {@code <effort>} is {@code
 * spotbugs-effort}, {@code <plugins>} (fb-contrib, find-sec-bugs) are a row.
 */
final class LintPlugins {

    static final String CHECKSTYLE = "maven-checkstyle-plugin";
    static final String PMD = "maven-pmd-plugin";
    static final String SPOTBUGS = "spotbugs-maven-plugin";

    private static final String TEST_ROOT = "src/test/java";
    private static final String MAVEN_PMD_DEFAULT = "maven-pmd-plugin-default.xml";

    private LintPlugins() {}

    /** The table, or null when the POM declares none of the three plugins. */
    static @Nullable PluginConfig map(Model model, ImportReport.Builder report) {
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> sources = new LinkedHashSet<>(List.of("src/main/java"));
        boolean any = false;
        Plugin checkstyle = PluginFacts.plugin(model, CHECKSTYLE).orElse(null);
        if (checkstyle != null) {
            any = true;
            checkstyle(checkstyle, baseDir, values, sources, report);
        }
        Plugin pmd = PluginFacts.plugin(model, PMD).orElse(null);
        if (pmd != null) {
            any = true;
            pmd(pmd, baseDir, values, sources, report);
        }
        Plugin spotbugs = PluginFacts.plugin(model, SPOTBUGS).orElse(null);
        if (spotbugs != null) {
            any = true;
            spotbugs(spotbugs, baseDir, values, sources, report);
        }
        if (!any) return null;
        if (sources.size() > 1) values.put("sources", List.copyOf(sources));
        report.warning("the lint plugins are `[lint]`: each tool runs as a cached step after compile and its findings"
                + " are diagnostics in jk-results.md with the rule id; `fail-on` says which severity fails the build.");
        return new PluginConfig("lint", values);
    }

    private static void checkstyle(
            Plugin plugin,
            @Nullable Path baseDir,
            Map<String, Object> values,
            Set<String> sources,
            ImportReport.Builder report) {
        String config = null;
        for (Xpp3Dom dom : PluginFacts.configurations(plugin)) {
            String location = PluginFacts.child(dom, "configLocation");
            if (location != null) config = location;
            if (EnvValues.parseBool(PluginFacts.child(dom, "includeTestSourceDirectory"))
                    .orElse(false)) {
                sources.add(TEST_ROOT);
            }
            String severity = PluginFacts.child(dom, "violationSeverity");
            if ("warning".equalsIgnoreCase(severity) || "info".equalsIgnoreCase(severity)) {
                values.put("fail-on", "warning");
            }
        }
        if (config == null || config.endsWith("sun_checks.xml") || config.endsWith("google_checks.xml")) {
            report.warning("`" + CHECKSTYLE + "` reads "
                    + (config == null ? "Checkstyle's default rule set" : "`" + config + "`")
                    + ", a rule set inside the plugin; `[lint] checkstyle` names a configuration file in the module,"
                    + " so copy the rule set in and point the key at it.");
            values.put("checkstyle", "config/checkstyle.xml");
        } else {
            values.put("checkstyle", SourceTreePlugins.moduleRelative(config, baseDir));
        }
        for (Dependency dependency : plugin.getDependencies()) {
            if ("checkstyle".equals(dependency.getArtifactId())
                    && "com.puppycrawl.tools".equals(dependency.getGroupId())) {
                String version = PluginFacts.usable(dependency.getVersion());
                if (version != null && !version.equals("14.1.0")) values.put("checkstyle-version", version);
            }
        }
    }

    private static void pmd(
            Plugin plugin,
            @Nullable Path baseDir,
            Map<String, Object> values,
            Set<String> sources,
            ImportReport.Builder report) {
        List<String> rulesets = new ArrayList<>();
        for (Xpp3Dom dom : PluginFacts.configurations(plugin)) {
            Xpp3Dom declared = dom.getChild("rulesets");
            if (declared != null) {
                for (Xpp3Dom ruleset : declared.getChildren()) {
                    String value = PluginFacts.usable(ruleset.getValue());
                    if (value == null) continue;
                    if (value.endsWith(MAVEN_PMD_DEFAULT)) {
                        report.warning("`" + PMD + "` uses the Maven plugin's own default ruleset; `[lint] pmd` names"
                                + " PMD's, so `rulesets/java/quickstart.xml` stands in — tune it to taste.");
                        rulesets.add("rulesets/java/quickstart.xml");
                    } else if (value.startsWith("/category/") || value.startsWith("/rulesets/")) {
                        rulesets.add(value.substring(1));
                    } else if (value.startsWith("category/") || value.startsWith("rulesets/")) {
                        rulesets.add(value);
                    } else {
                        rulesets.add(SourceTreePlugins.moduleRelative(value.replaceFirst("^file://", ""), baseDir));
                    }
                }
            }
            if (EnvValues.parseBool(PluginFacts.child(dom, "includeTests")).orElse(false)) sources.add(TEST_ROOT);
            for (String name : List.of("excludeFromFailureFile", "excludeRoots", "excludes")) {
                if (dom.getChild(name) != null) {
                    report.warning("`" + PMD + "` `<" + name + ">` has no `[lint]` key; suppress a finding in the"
                            + " ruleset or with PMD's own `@SuppressWarnings(\"PMD.Rule\")`.");
                }
            }
        }
        if (rulesets.isEmpty()) rulesets.add("rulesets/java/quickstart.xml");
        values.put("pmd", rulesets);
    }

    private static void spotbugs(
            Plugin plugin,
            @Nullable Path baseDir,
            Map<String, Object> values,
            Set<String> sources,
            ImportReport.Builder report) {
        values.put("spotbugs", true);
        for (Xpp3Dom dom : PluginFacts.configurations(plugin)) {
            String exclude = PluginFacts.child(dom, "excludeFilterFile");
            if (exclude != null) values.put("spotbugs-exclude", SourceTreePlugins.moduleRelative(exclude, baseDir));
            String effort = PluginFacts.child(dom, "effort");
            if (effort != null && !effort.equalsIgnoreCase("default")) {
                values.put("spotbugs-effort", effort.toLowerCase(Locale.ROOT));
            }
            if (EnvValues.parseBool(PluginFacts.child(dom, "includeTests")).orElse(false)) sources.add(TEST_ROOT);
            if (dom.getChild("plugins") != null) {
                report.warning("`" + SPOTBUGS + "` `<plugins>` (fb-contrib, find-sec-bugs) have no `[lint]` key; the"
                        + " step runs SpotBugs's own detectors.");
            }
        }
        String version = PluginFacts.usable(plugin.getVersion());
        if (version != null) {
            // The Maven plugin's version is SpotBugs's plus a plugin digit: 4.10.4.1 runs SpotBugs 4.10.4.
            int dots = version.length() - version.replace(".", "").length();
            String spotbugs = dots == 3 ? version.substring(0, version.lastIndexOf('.')) : version;
            if (!spotbugs.equals("4.10.4")) values.put("spotbugs-version", spotbugs);
        }
    }
}
