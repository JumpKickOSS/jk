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
 * one, Maven's own default ruleset included, a {@code file://} URL a module file — {@code
 * <excludeFromFailureFile>} is {@code pmd-exclude}, {@code <includeTests>} adds the test root, and
 * PMD's threshold is its own: {@code <failOnViolation>false</failOnViolation>} is {@code never}, a
 * {@code <failurePriority>} of 1 or 2 is {@code error}, anything else — Maven's default of 5 fails
 * on every finding — is {@code warning}. The PMD release is the plugin's: the {@code pmd-java} its
 * own {@code <dependencies>} pin, else the one the plugin version bundles, written as {@code
 * pmd-version}; a plugin still on PMD 6 is a row, since the step runs PMD 7. {@code
 * spotbugs-maven-plugin}: {@code spotbugs = true}, {@code <excludeFilterFile>} is {@code
 * spotbugs-exclude}, {@code <effort>} is {@code spotbugs-effort}, {@code <threshold>} is {@code
 * spotbugs-threshold}, {@code <plugins>} (fb-contrib, find-sec-bugs) are a row; {@code
 * spotbugs:check} fails on any bug at the confidence threshold, so its threshold is {@code
 * warning}, or {@code never} under {@code <failOnError>false</failOnError>}.
 *
 * <p>Each tool fails the Maven build on its own terms, and the table says the same: tools that
 * agree share one {@code fail-on}, tools that differ each carry their {@code <tool>-fail-on}, and
 * the default {@code error} is never written.
 *
 * <p>A lint plugin that binds no {@code <execution>} runs under Maven only by hand ({@code mvn
 * pmd:check}). A module declaring one in its own POM gets the table all the same — the tool is
 * that module's, and jk runs it on every build — while one that only inherits it from a parent gets
 * no table and the parent's declaration is one row, counted over the modules it reaches.
 */
final class LintPlugins {

    static final String CHECKSTYLE = "maven-checkstyle-plugin";
    static final String PMD = "maven-pmd-plugin";
    static final String SPOTBUGS = "spotbugs-maven-plugin";

    private static final String TEST_ROOT = "src/test/java";
    /** The ruleset {@code maven-pmd-plugin} runs when a POM names none, as the lint step spells it. */
    private static final String MAVEN_PMD_DEFAULT = "rulesets/java/maven-pmd-plugin-default.xml";

    /** The PMD release the lint step runs when the table names none. */
    private static final String DEFAULT_PMD = "7.27.0";

    /** The PMD each {@code maven-pmd-plugin} release bundles, from the plugin POM's {@code pmdVersion}. */
    private static final Map<String, String> PMD_BY_PLUGIN = Map.of(
            "3.20.0", "6.53.0",
            "3.21.0", "6.55.0",
            "3.21.2", "6.55.0",
            "3.22.0", "7.0.0",
            "3.23.0", "7.0.0",
            "3.24.0", "7.3.0",
            "3.25.0", "7.3.0",
            "3.26.0", "7.7.0",
            "3.27.0", "7.14.0",
            "3.28.0", "7.17.0");

    private LintPlugins() {}

    /**
     * The table, or null when the POM declares none of the three plugins — or only inherits ones
     * that bind no execution. {@code inherited} collects the rows of a workspace module; null for a
     * POM imported on its own.
     */
    static @Nullable PluginConfig map(
            EffectiveModel em, ImportReport.Builder report, @Nullable InheritedRows inherited) {
        Model model = em.model();
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> sources = new LinkedHashSet<>(List.of("src/main/java"));
        Map<String, String> failOn = new LinkedHashMap<>();
        boolean any = false;
        Plugin checkstyle = bound(em, CHECKSTYLE, "checkstyle:check", report, inherited);
        if (checkstyle != null) {
            any = true;
            failOn.put("checkstyle", checkstyle(checkstyle, baseDir, values, sources, report));
        }
        Plugin pmd = bound(em, PMD, "pmd:check", report, inherited);
        if (pmd != null) {
            any = true;
            failOn.put("pmd", pmd(pmd, baseDir, values, sources, report));
        }
        Plugin spotbugs = bound(em, SPOTBUGS, "spotbugs:check", report, inherited);
        if (spotbugs != null) {
            any = true;
            failOn.put("spotbugs", spotbugs(spotbugs, baseDir, values, sources, report));
        }
        if (!any) return null;
        if (sources.size() > 1) values.put("sources", List.copyOf(sources));
        failOn(failOn, values);
        report.warning("the lint plugins are `[lint]`: each tool runs as a cached step after compile and its findings"
                + " are diagnostics in jk-results.md with the rule id; `fail-on` says which severity fails the build.");
        return new PluginConfig("lint", values);
    }

    /**
     * The lint plugin {@code artifactId} when the module gets its table: declared in the module's
     * own POM, or inherited with an execution bound. One inherited with none is null and a row —
     * at the declaring POM, once, when {@code inherited} collects rows.
     */
    private static @Nullable Plugin bound(
            EffectiveModel em,
            String artifactId,
            String goal,
            ImportReport.Builder report,
            @Nullable InheritedRows inherited) {
        Plugin plugin = PluginFacts.plugin(em.model(), artifactId).orElse(null);
        if (plugin == null) return null;
        boolean own = InheritedRows.declaresPlugin(em.raw(), artifactId, em.activeProfiles());
        if (own || !plugin.getExecutions().isEmpty()) return plugin;
        String key = artifactId.equals(CHECKSTYLE) ? "checkstyle" : artifactId.equals(PMD) ? "pmd" : "spotbugs";
        String message = "`" + artifactId + "` binds no `<execution>`, so Maven runs it only by hand (`mvn " + goal
                + "`); no `[lint] " + key + "` was written for the modules inheriting it. Declare the table on a"
                + " module to lint it on every build.";
        if (inherited == null) {
            report.warning(message);
        } else {
            inherited.inherited(
                    inherited.declaredBy(em, raw -> InheritedRows.declaresPlugin(raw, artifactId)),
                    ImportReport.Severity.WARNING,
                    message);
        }
        return null;
    }

    /**
     * The thresholds, keyed by tool id: one {@code fail-on} when every tool shares it, else each
     * tool's own {@code <tool>-fail-on}; the default {@code error} is left unwritten either way.
     */
    private static void failOn(Map<String, String> byTool, Map<String, Object> values) {
        if (byTool.isEmpty()) return;
        if (byTool.values().stream().distinct().count() == 1) {
            String shared = byTool.values().iterator().next();
            if (!shared.equals("error")) values.put("fail-on", shared);
            return;
        }
        byTool.forEach((tool, level) -> {
            if (!level.equals("error")) values.put(tool + "-fail-on", level);
        });
    }

    /** Maps the plugin and returns the {@code fail-on} Checkstyle's {@code <violationSeverity>} means. */
    private static String checkstyle(
            Plugin plugin,
            @Nullable Path baseDir,
            Map<String, Object> values,
            Set<String> sources,
            ImportReport.Builder report) {
        String config = null;
        String failOn = "error";
        for (Xpp3Dom dom : PluginFacts.configurations(plugin)) {
            String location = PluginFacts.text(dom.getChild("configLocation"));
            if (location != null && !location.isBlank()) config = location.strip();
            if (EnvValues.parseBool(PluginFacts.child(dom, "includeTestSourceDirectory"))
                    .orElse(false)) {
                sources.add(TEST_ROOT);
            }
            String severity = PluginFacts.child(dom, "violationSeverity");
            if ("warning".equalsIgnoreCase(severity) || "info".equalsIgnoreCase(severity)) failOn = "warning";
            if (!EnvValues.parseBool(PluginFacts.child(dom, "failsOnError")).orElse(true)
                    || !EnvValues.parseBool(PluginFacts.child(dom, "failOnViolation"))
                            .orElse(true)) {
                failOn = "never";
            }
            String excludes = PluginFacts.child(dom, "excludes");
            if (excludes != null) {
                List<String> globs = new ArrayList<>();
                for (String glob : excludes.split(",")) {
                    if (!glob.isBlank()) globs.add(glob.strip());
                }
                if (!globs.isEmpty()) values.put("exclude", globs);
            }
        }
        // A rule set the module does not hold is written as the POM spelled it, so the step's
        // warning names it; the step runs nothing until the file is there.
        boolean url = config != null && (config.startsWith("http://") || config.startsWith("https://"));
        boolean property = config != null && config.contains("${");
        boolean builtIn = config != null && (config.endsWith("sun_checks.xml") || config.endsWith("google_checks.xml"));
        if (config == null || url || property || builtIn) {
            String named = config == null ? "sun_checks.xml" : config;
            report.warning("`" + CHECKSTYLE + "` reads "
                    + (config == null ? "Checkstyle's default rule set" : "`" + config + "`")
                    + (url
                            ? ", a rule set at a URL"
                            : property
                                    ? ", a path through a property no POM defines"
                                    : ", a rule set inside the plugin")
                    + "; `[lint] checkstyle` names a configuration file in the module,"
                    + " so copy the rule set in and point the key at it — until then the step lints nothing"
                    + " and says so.");
            values.put("checkstyle", named);
        } else {
            values.put("checkstyle", SourceTreePlugins.moduleRelativeFile(config, baseDir));
        }
        for (Dependency dependency : plugin.getDependencies()) {
            if ("checkstyle".equals(dependency.getArtifactId())
                    && "com.puppycrawl.tools".equals(dependency.getGroupId())) {
                String version = PluginFacts.usable(dependency.getVersion());
                if (version != null && !version.equals("14.1.0")) values.put("checkstyle-version", version);
            }
        }
        return failOn;
    }

    /**
     * Maps the plugin and returns the {@code fail-on} its threshold means: Maven fails on every
     * violation whose priority is at most {@code <failurePriority>} (5 by default, so every one),
     * and jk reads priorities 1 and 2 as errors, so a threshold of 1 or 2 is {@code error} and any
     * other {@code warning}; {@code <failOnViolation>false</failOnViolation>} is {@code never}.
     */
    private static String pmd(
            Plugin plugin,
            @Nullable Path baseDir,
            Map<String, Object> values,
            Set<String> sources,
            ImportReport.Builder report) {
        List<String> rulesets = new ArrayList<>();
        String failOn = "warning";
        for (Xpp3Dom dom : PluginFacts.configurations(plugin)) {
            Xpp3Dom declared = dom.getChild("rulesets");
            if (declared != null) {
                for (Xpp3Dom ruleset : declared.getChildren()) {
                    String value = PluginFacts.usable(ruleset.getValue());
                    if (value == null) {
                        if (ruleset.getValue() != null && !ruleset.getValue().isBlank()) {
                            report.warning("`" + PMD + "` names the ruleset `"
                                    + ruleset.getValue().trim()
                                    + "` through a property no POM defines; `rulesets/java/quickstart.xml` stands in —"
                                    + " point `[lint] pmd` at the module's ruleset file.");
                            rulesets.add("rulesets/java/quickstart.xml");
                        }
                        continue;
                    }
                    if (value.startsWith("/category/") || value.startsWith("/rulesets/")) {
                        rulesets.add(value.substring(1));
                    } else if (value.startsWith("category/") || value.startsWith("rulesets/")) {
                        rulesets.add(value);
                    } else {
                        rulesets.add(SourceTreePlugins.moduleRelativeFile(value.replaceFirst("^file://", ""), baseDir));
                    }
                }
            }
            if (EnvValues.parseBool(PluginFacts.child(dom, "includeTests")).orElse(false)) sources.add(TEST_ROOT);
            String exclude = PluginFacts.child(dom, "excludeFromFailureFile");
            if (exclude != null) values.put("pmd-exclude", SourceTreePlugins.moduleRelativeFile(exclude, baseDir));
            for (String name : List.of("excludeRoots", "excludes")) {
                if (dom.getChild(name) != null) {
                    report.warning("`" + PMD + "` `<" + name + ">` has no `[lint]` key; suppress a finding in the"
                            + " ruleset or with PMD's own `@SuppressWarnings(\"PMD.Rule\")`.");
                }
            }
            String priority = PluginFacts.child(dom, "failurePriority");
            if (priority != null && priority.matches("[12]")) failOn = "error";
            if (!EnvValues.parseBool(PluginFacts.child(dom, "failOnViolation")).orElse(true)) failOn = "never";
        }
        if (rulesets.isEmpty()) rulesets.add(MAVEN_PMD_DEFAULT);
        values.put("pmd", rulesets);
        pmdVersion(plugin, values, report);
        return failOn;
    }

    /**
     * {@code pmd-version}: the {@code pmd-java} (or {@code pmd-core}) the plugin's own dependencies
     * pin, else the PMD the plugin release bundles. A PMD 6 is not written — the step runs PMD 7's
     * command line — and is a row; jk's own default is not written either.
     */
    private static void pmdVersion(Plugin plugin, Map<String, Object> values, ImportReport.Builder report) {
        String version = null;
        for (Dependency dependency : plugin.getDependencies()) {
            if ("net.sourceforge.pmd".equals(dependency.getGroupId())
                    && ("pmd-java".equals(dependency.getArtifactId())
                            || "pmd-core".equals(dependency.getArtifactId()))) {
                version = PluginFacts.usable(dependency.getVersion());
            }
        }
        if (version == null) {
            String pluginVersion = PluginFacts.usable(plugin.getVersion());
            if (pluginVersion == null) return;
            version = PMD_BY_PLUGIN.get(pluginVersion);
            if (version == null) return;
        }
        if (version.startsWith("6.")) {
            report.warning("`" + PMD + "` runs PMD " + version + "; the lint step runs PMD 7 (" + DEFAULT_PMD
                    + " unless `pmd-version` says otherwise), whose rulesets and rule names differ from PMD 6's —"
                    + " a finding the Maven build did not report may be the newer release's.");
            return;
        }
        if (!version.equals(DEFAULT_PMD)) values.put("pmd-version", version);
    }

    /**
     * Maps the plugin and returns the threshold {@code spotbugs:check} applies: every bug at the
     * confidence threshold fails the build ({@code warning}), none under {@code
     * <failOnError>false</failOnError>} ({@code never}).
     */
    private static String spotbugs(
            Plugin plugin,
            @Nullable Path baseDir,
            Map<String, Object> values,
            Set<String> sources,
            ImportReport.Builder report) {
        values.put("spotbugs", true);
        String failOn = "warning";
        for (Xpp3Dom dom : PluginFacts.configurations(plugin)) {
            if (!EnvValues.parseBool(PluginFacts.child(dom, "failOnError")).orElse(true)) failOn = "never";
            String exclude = PluginFacts.child(dom, "excludeFilterFile");
            if (exclude != null) values.put("spotbugs-exclude", SourceTreePlugins.moduleRelativeFile(exclude, baseDir));
            String effort = PluginFacts.child(dom, "effort");
            if (effort != null && !effort.equalsIgnoreCase("default")) {
                values.put("spotbugs-effort", effort.toLowerCase(Locale.ROOT));
            }
            String threshold = PluginFacts.child(dom, "threshold");
            if (threshold != null) {
                switch (threshold.toLowerCase(Locale.ROOT)) {
                    case "high" -> values.put("spotbugs-threshold", "high");
                    case "low", "exp", "ignore" -> values.put("spotbugs-threshold", "low");
                    default -> {
                        /* Default / Medium: the step's own floor */
                    }
                }
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
        return failOn;
    }
}
