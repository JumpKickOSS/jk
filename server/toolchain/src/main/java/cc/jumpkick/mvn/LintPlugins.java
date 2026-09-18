// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.PomParser;
import cc.jumpkick.repo.RepoGroup;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * The lint plugins are one {@code [lint]} table. {@code maven-checkstyle-plugin}: each execution
 * binding {@code check} is one Checkstyle run — the first the table's own keys, every further one
 * a {@code [lint.<execution-id>]} entry, an execution Maven skips a row — and in each {@code
 * <configLocation>} is {@code checkstyle} — a URL as written, which the step fetches; a path
 * through {@code ${maven.multiModuleProjectDirectory}} the reactor root's file by its path from
 * the module; a built-in {@code sun_checks.xml} / {@code google_checks.xml} is a row, since the
 * key reads a file in the module; a name the module does not hold is a resource of the plugin's
 * {@code <dependencies>}, which are {@code checkstyle-classpath} — {@code <suppressionsLocation>}
 * is {@code checkstyle-suppressions}, {@code <headerLocation>} {@code checkstyle-header}, {@code
 * <propertyExpansion>} {@code checkstyle-properties}, the Checkstyle
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

    /** The property every {@code maven-pmd-plugin} release's POM names its bundled PMD under. */
    private static final String PMD_VERSION_PROPERTY = "pmdVersion";

    private LintPlugins() {}

    /**
     * The table, or null when the POM declares none of the three plugins — or only inherits ones
     * that bind no execution. {@code inherited} collects the rows of a workspace module; null for a
     * POM imported on its own. {@code repos} serves the PMD plugin's own POM, which names the PMD
     * it bundles.
     */
    static @Nullable PluginConfig map(
            EffectiveModel em, ImportReport.Builder report, @Nullable InheritedRows inherited, RepoGroup repos) {
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
            Path reactorRoot = inherited != null ? inherited.rootDir() : reactorRoot(em, baseDir);
            failOn.put("checkstyle", checkstyle(checkstyle, baseDir, reactorRoot, values, sources, report));
        }
        Plugin pmd = bound(em, PMD, "pmd:check", report, inherited);
        if (pmd != null) {
            any = true;
            failOn.put("pmd", pmd(pmd, baseDir, values, sources, report, repos));
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

    /** The launcher property Maven sets to the reactor root, which no POM defines. */
    private static final String REACTOR_ROOT_PROPERTY = "${maven.multiModuleProjectDirectory}";

    /**
     * The reactor root of a POM imported on its own, as Maven's launcher finds it from the module:
     * the nearest directory at or above the module holding {@code .mvn}, else the directory of the
     * farthest parent read from disk through {@code <relativePath>} — the top of the reactor the
     * module belongs to — else the module's own; null when the POM has no directory.
     */
    static @Nullable Path reactorRoot(EffectiveModel em, @Nullable Path baseDir) {
        if (baseDir == null) return null;
        Path module = baseDir.toAbsolutePath().normalize();
        for (Path dir = module; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(".mvn"))) return dir;
        }
        Path top = module;
        for (EffectiveModel.Ancestor ancestor : em.ancestors()) {
            File pom = ancestor.raw().getPomFile();
            if (pom == null) continue;
            Path dir = pom.toPath().toAbsolutePath().normalize().getParent();
            if (dir != null) top = dir;
        }
        return top;
    }

    /**
     * {@code value} as the module names it: a path through {@value #REACTOR_ROOT_PROPERTY} is the
     * root's file by its path from the module ({@code ../../src/checkstyle/checks.xml}), any other
     * a module-relative file as {@link SourceTreePlugins#moduleRelativeFile}.
     */
    static String launcherPath(String value, @Nullable Path baseDir, @Nullable Path reactorRoot) {
        if (value.contains(REACTOR_ROOT_PROPERTY) && baseDir != null && reactorRoot != null) {
            String rest = value.replace('\\', '/').replace(REACTOR_ROOT_PROPERTY, "");
            while (rest.startsWith("/")) rest = rest.substring(1);
            Path file = reactorRoot.toAbsolutePath().normalize().resolve(rest).normalize();
            return baseDir.toAbsolutePath()
                    .normalize()
                    .relativize(file)
                    .toString()
                    .replace('\\', '/');
        }
        return SourceTreePlugins.moduleRelativeFile(value, baseDir);
    }

    /**
     * One Checkstyle run as Maven makes it: an execution's configuration merged over the plugin's
     * own, or the plugin's own when no execution binds. {@code id} is the execution's, null for the
     * plugin-level run.
     */
    private record Run(@Nullable String id, Xpp3Dom dom) {}

    /**
     * The runs the plugin's executions make, in order: each execution binding {@code check} (or
     * {@code checkstyle}) is one, its configuration over the plugin's; an execution Maven skips
     * ({@code <skip>true</skip>}) is a row and no run; two executions over one rule set are one
     * run. Without such an execution the plugin's own configuration is the one run.
     */
    private static List<Run> checkstyleRuns(Plugin plugin, ImportReport.Builder report) {
        Xpp3Dom base = plugin.getConfiguration() instanceof Xpp3Dom dom ? dom : new Xpp3Dom("configuration");
        List<Run> runs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PluginExecution execution : plugin.getExecutions()) {
            List<String> goals = execution.getGoals();
            if (!goals.isEmpty() && !goals.contains("check") && !goals.contains("checkstyle")) continue;
            Xpp3Dom own = execution.getConfiguration() instanceof Xpp3Dom dom ? new Xpp3Dom(dom) : null;
            Xpp3Dom merged = own == null ? base : Objects.requireNonNull(Xpp3Dom.mergeXpp3Dom(own, base));
            if (EnvValues.parseBool(PluginFacts.child(merged, "skip")).orElse(false)) {
                report.warning("execution `" + execution.getId() + "` of `" + CHECKSTYLE
                        + "` is skipped under Maven (`<skip>` is true), so no Checkstyle run was written for it.");
                continue;
            }
            String location = PluginFacts.text(merged.getChild("configLocation"));
            if (!seen.add(location == null ? "" : location.strip())) continue;
            runs.add(new Run(execution.getId(), merged));
        }
        if (runs.isEmpty()) runs.add(new Run(null, base));
        return runs;
    }

    /**
     * Maps the plugin — its first run as the table's own keys, every further run as a {@code
     * [lint.<execution-id>]} entry — and returns the threshold the first run's {@code
     * <violationSeverity>} means. The plugin's own {@code <dependencies>} other than Checkstyle
     * itself are {@code checkstyle-classpath}, on the table and on every entry: the jars a rule
     * set, header, suppressions or check classes live in as resources.
     */
    private static String checkstyle(
            Plugin plugin,
            @Nullable Path baseDir,
            @Nullable Path reactorRoot,
            Map<String, Object> values,
            Set<String> sources,
            ImportReport.Builder report) {
        List<String> classpath = new ArrayList<>();
        for (Dependency dependency : plugin.getDependencies()) {
            String version = PluginFacts.usable(dependency.getVersion());
            if (version == null) continue;
            if ("checkstyle".equals(dependency.getArtifactId())
                    && "com.puppycrawl.tools".equals(dependency.getGroupId())) {
                if (!version.equals("14.1.0")) values.put("checkstyle-version", version);
                continue;
            }
            classpath.add(dependency.getGroupId() + ":" + dependency.getArtifactId() + ":" + version);
        }
        List<Run> runs = checkstyleRuns(plugin, report);
        Run first = runs.getFirst();
        if (!classpath.isEmpty()) values.put("checkstyle-classpath", List.copyOf(classpath));
        String failOn = checkstyleRun(first.dom(), baseDir, reactorRoot, values, sources, !classpath.isEmpty(), report);
        Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        for (Run run : runs.subList(1, runs.size())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            Set<String> roots = new LinkedHashSet<>(List.of("src/main/java"));
            if (!classpath.isEmpty()) entry.put("checkstyle-classpath", List.copyOf(classpath));
            String threshold =
                    checkstyleRun(run.dom(), baseDir, reactorRoot, entry, roots, !classpath.isEmpty(), report);
            if (roots.size() != 1 || !roots.contains("src/main/java")) entry.put("sources", List.copyOf(roots));
            if (!threshold.equals("error")) entry.put("fail-on", threshold);
            entries.put(Objects.requireNonNull(run.id()), entry);
        }
        if (!entries.isEmpty()) values.put(PluginConfig.ENTRIES, entries);
        return failOn;
    }

    /**
     * One run's keys into {@code values} from its merged configuration, and the threshold its
     * {@code <violationSeverity>} means. {@code <configLocation>} is {@code checkstyle}, {@code
     * <suppressionsLocation>} {@code checkstyle-suppressions}, {@code <headerLocation>} {@code
     * checkstyle-header}; {@code <propertyExpansion>} lines are {@code checkstyle-properties},
     * except {@code checkstyle.suppressions.file} and {@code checkstyle.header.file}, which are the
     * keys above; {@code <sourceDirectories>} and {@code <includeTestSourceDirectory>} shape
     * {@code sources}. A location the module does not hold is written as spelled when the run has
     * a classpath — a resource in one of its jars — and a row otherwise.
     */
    private static String checkstyleRun(
            Xpp3Dom dom,
            @Nullable Path baseDir,
            @Nullable Path reactorRoot,
            Map<String, Object> values,
            Set<String> sources,
            boolean classpath,
            ImportReport.Builder report) {
        String failOn = "error";
        String config = PluginFacts.text(dom.getChild("configLocation"));
        String suppressions = PluginFacts.text(dom.getChild("suppressionsLocation"));
        String header = PluginFacts.text(dom.getChild("headerLocation"));
        Map<String, String> properties = new LinkedHashMap<>();
        String expansion = PluginFacts.text(dom.getChild("propertyExpansion"));
        if (expansion != null) {
            for (String line : expansion.split("\\R")) {
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String name = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (name.isEmpty() || value.isEmpty()) continue;
                switch (name) {
                    case "checkstyle.suppressions.file" -> suppressions = suppressions == null ? value : suppressions;
                    case "checkstyle.header.file" -> header = header == null ? value : header;
                    default -> properties.put(name, propertyValue(value, baseDir, reactorRoot));
                }
            }
        }
        Xpp3Dom directories = dom.getChild("sourceDirectories");
        if (directories != null) {
            List<String> roots = new ArrayList<>();
            for (Xpp3Dom child : directories.getChildren()) {
                String dir = PluginFacts.usable(child.getValue());
                if (dir != null) roots.add(sourceRoot(dir, baseDir));
            }
            String inline = PluginFacts.usable(directories.getValue());
            if (roots.isEmpty() && inline != null) roots.add(sourceRoot(inline, baseDir));
            if (!roots.isEmpty()) {
                sources.clear();
                sources.addAll(roots);
            }
        }
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
        values.put(
                "checkstyle", ruleSet(config == null ? null : config.strip(), baseDir, reactorRoot, classpath, report));
        if (suppressions != null) {
            location(
                    suppressions.strip(),
                    "checkstyle-suppressions",
                    "suppressions",
                    baseDir,
                    reactorRoot,
                    values,
                    report);
        }
        if (header != null)
            location(header.strip(), "checkstyle-header", "header", baseDir, reactorRoot, values, report);
        if (!properties.isEmpty()) values.put("checkstyle-properties", properties);
        return failOn;
    }

    /** A {@code <sourceDirectories>} entry as a module-relative root: {@code ./} and {@code .} are the module itself. */
    private static String sourceRoot(String dir, @Nullable Path baseDir) {
        String root = Path.of(SourceTreePlugins.moduleRelativeFile(dir, baseDir))
                .normalize()
                .toString()
                .replace('\\', '/');
        return root.isEmpty() ? "." : root;
    }

    /**
     * The {@code checkstyle} key for {@code config}: a URL as written, which the step fetches; a
     * path through the reactor-root launcher property the root's file by its path from the module;
     * a built-in {@code sun_checks.xml} / {@code google_checks.xml}, or a path through a property
     * no POM defines, as spelled plus a row to copy the rule set in; any other name the module's
     * file, or, when the run has a {@code classpath}, the resource one of its jars holds.
     */
    private static String ruleSet(
            @Nullable String config,
            @Nullable Path baseDir,
            @Nullable Path reactorRoot,
            boolean classpath,
            ImportReport.Builder report) {
        if (config != null && reactorRoot != null && config.contains(REACTOR_ROOT_PROPERTY)) {
            config = launcherPath(config, baseDir, reactorRoot);
        }
        boolean url = config != null && (config.startsWith("http://") || config.startsWith("https://"));
        boolean property = config != null && config.contains("${");
        boolean builtIn = config != null && (config.endsWith("sun_checks.xml") || config.endsWith("google_checks.xml"));
        if (config == null || property || builtIn) {
            String named = config == null ? "sun_checks.xml" : config;
            report.warning("`" + CHECKSTYLE + "` reads "
                    + (config == null ? "Checkstyle's default rule set" : "`" + config + "`")
                    + (property ? ", a path through a property no POM defines" : ", a rule set inside the plugin")
                    + "; `[lint] checkstyle` names a configuration file in the module,"
                    + " so copy the rule set in and point the key at it — until then the step lints nothing"
                    + " and says so.");
            return named;
        }
        if (url) return config;
        String file = SourceTreePlugins.moduleRelativeFile(config, baseDir);
        return classpath && (baseDir == null || !Files.isRegularFile(baseDir.resolve(file))) ? config : file;
    }

    /**
     * {@code key} for a suppressions or header {@code location}: a URL, a module path (through the
     * reactor-root launcher property when spelled so) or a classpath resource as written; a path
     * through a property no POM defines is a row instead.
     */
    private static void location(
            String location,
            String key,
            String what,
            @Nullable Path baseDir,
            @Nullable Path reactorRoot,
            Map<String, Object> values,
            ImportReport.Builder report) {
        String resolved = launcherPath(location, baseDir, reactorRoot);
        if (resolved.startsWith("http://") || resolved.startsWith("https://") || !resolved.contains("${")) {
            values.put(key, resolved);
            return;
        }
        report.warning("`" + CHECKSTYLE + "` reads its " + what + " from `" + location
                + "`, a path through a property no POM defines; `[lint] " + key + "` names a file in the module,"
                + " so copy the " + what + " in and point the key at it.");
    }

    /**
     * A {@code <propertyExpansion>} value as the module names it: a path through the reactor-root
     * launcher property, or an absolute path under the module or the reactor root, by its path from
     * the module — so {@code ${project.build.directory}} is {@code target} — and anything else as
     * written.
     */
    private static String propertyValue(String value, @Nullable Path baseDir, @Nullable Path reactorRoot) {
        if (value.contains(REACTOR_ROOT_PROPERTY)) return launcherPath(value, baseDir, reactorRoot);
        if (baseDir == null || value.contains("${") || value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException notAPath) {
            return value;
        }
        if (!path.isAbsolute()) return value;
        Path module = baseDir.toAbsolutePath().normalize();
        Path target = path.normalize();
        if (target.startsWith(module)
                || (reactorRoot != null
                        && target.startsWith(reactorRoot.toAbsolutePath().normalize()))) {
            return module.relativize(target).toString().replace('\\', '/');
        }
        return value;
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
            ImportReport.Builder report,
            RepoGroup repos) {
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
        pmdVersion(plugin, values, report, repos);
        return failOn;
    }

    /**
     * {@code pmd-version}: the {@code pmd-java} (or {@code pmd-core}) the plugin's own dependencies
     * pin, else the PMD the plugin release bundles — the {@code pmdVersion} property of its POM,
     * read from the repositories. A PMD 6 is not written — the step runs PMD 7's command line —
     * and is a row; jk's own default is not written either; a POM no repository serves is a row.
     */
    private static void pmdVersion(
            Plugin plugin, Map<String, Object> values, ImportReport.Builder report, RepoGroup repos) {
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
            version = bundledPmd(pluginVersion, repos, report);
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
     * The PMD {@code maven-pmd-plugin} {@code pluginVersion} bundles, from the {@code pmdVersion}
     * property of the release's POM; null, with a row, when no repository serves the POM or it
     * names none.
     */
    static @Nullable String bundledPmd(String pluginVersion, RepoGroup repos, ImportReport.Builder report) {
        Coordinate coord = Coordinate.parse("org.apache.maven.plugins:" + PMD + ":" + pluginVersion + "!pom");
        String why;
        try {
            Optional<RepoGroup.RepoFetched> fetched = repos.tryFetchPom(coord);
            if (fetched.isPresent()) {
                Pom pom = PomParser.parse(
                        Files.readAllBytes(fetched.get().fetched().cachePath()));
                String version = PluginFacts.usable(pom.properties().get(PMD_VERSION_PROPERTY));
                if (version != null) return version;
                why = "its POM names no `" + PMD_VERSION_PROPERTY + "`";
            } else {
                why = "no repository serves its POM";
            }
        } catch (IOException | RuntimeException e) {
            why = "its POM could not be read: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            why = "the read of its POM was interrupted";
        }
        report.warning("`" + PMD + "` " + pluginVersion + " bundles a PMD release this import could not learn — " + why
                + "; `pmd-version` is left to the step's default (" + DEFAULT_PMD + "), so set it to the PMD the"
                + " Maven build ran if a finding the build did not report appears.");
        return null;
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
