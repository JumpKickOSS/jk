// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.TaskSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The lint plugin's code layer: one step per tool the {@code [lint]} table enables, each after
 * compile — the sources, the tool's configuration, the compiled classes and the table are its
 * cache key — forking the tool over the module and reporting its findings as the step's own
 * diagnostics, plus one Checkstyle step per {@code [lint.<name>]} entry, a run over a rule set of
 * its own. A clean module is a cache hit on the next build; a finding at or above {@code fail-on}
 * fails the step, a finding below it is a warning on the report.
 */
public final class LintPlugin implements Plugin, BuildExtension {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-lint", "##JKLINT:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        PluginConfig config = ctx.config();
        for (LintTool tool : enabled(config)) ctx.task(task(tool, config));
        for (String run : checkstyleRuns(config)) ctx.task(task(LintTool.CHECKSTYLE, scoped(config, run), run));
    }

    /** The {@code [lint.<name>]} entries: each names a rule set and is a Checkstyle run of its own. */
    static List<String> checkstyleRuns(PluginConfig config) {
        return List.copyOf(config.entries().keySet());
    }

    /** The keys that describe one Checkstyle run, which an entry carries for itself and never inherits from the table. */
    private static final List<String> RUN_KEYS = List.of(
            "checkstyle",
            "checkstyle-suppressions",
            "checkstyle-header",
            "checkstyle-properties",
            "checkstyle-classpath",
            "sources",
            "exclude",
            "fail-on",
            "checkstyle-fail-on");

    /**
     * The configuration one {@code [lint.<run>]} run reads: the entry's own values over the table's
     * shared ones ({@code checkstyle-version}), with the table's own run and thresholds left out —
     * a run's {@code fail-on} is its own, {@code error} unless the entry writes it.
     */
    static PluginConfig scoped(PluginConfig table, String run) {
        Map<String, Object> values = new LinkedHashMap<>(table.values());
        values.remove(PluginConfig.ENTRIES);
        RUN_KEYS.forEach(values::remove);
        values.putAll(Objects.requireNonNull(table.entries().get(run), run));
        return new PluginConfig(table.id(), values);
    }

    /** The tools the table turns on: a configuration named, rulesets listed, or a switch set. */
    static Set<LintTool> enabled(PluginConfig config) {
        Set<LintTool> tools = EnumSet.noneOf(LintTool.class);
        if (config.stringOpt("checkstyle").isPresent()) tools.add(LintTool.CHECKSTYLE);
        if (!config.stringList("pmd").isEmpty()) tools.add(LintTool.PMD);
        if (config.bool("spotbugs", false)) tools.add(LintTool.SPOTBUGS);
        if (config.bool("detekt", false)) tools.add(LintTool.DETEKT);
        return tools;
    }

    /**
     * One tool's step: the source roots it reads and its configuration files are project inputs,
     * the classes dir orders it after compile (and is what SpotBugs analyses), the table's values
     * complete the key; the report it writes is the declared output.
     */
    static TaskSpec task(LintTool tool, PluginConfig config) {
        return task(tool, config, null);
    }

    /** {@link #task(LintTool, PluginConfig)} for one {@code [lint.<run>]} Checkstyle run, over its scoped configuration. */
    static TaskSpec task(LintTool tool, PluginConfig config, @Nullable String run) {
        List<In> ins = new ArrayList<>();
        for (String root : sourceRoots(tool, config)) ins.add(In.projectFiles(root));
        for (String file : configFiles(tool, config)) ins.add(In.projectFiles(file));
        ins.add(In.classes());
        if (tool == LintTool.SPOTBUGS) ins.add(In.compileClasspath());
        ins.add(In.config());
        return TaskSpec.named(tool.stepName(run))
                .inputs(ins.toArray(In[]::new))
                .outputs(tool.out(run))
                .run(exec -> LintStep.run(exec, tool, run));
    }

    /** The module-relative source roots {@code tool} reads: the Kotlin roots for detekt, the Java roots otherwise. */
    static List<String> sourceRoots(LintTool tool, PluginConfig config) {
        List<String> roots = config.stringList(tool == LintTool.DETEKT ? "kotlin-sources" : "sources");
        if (!roots.isEmpty()) return roots;
        return List.of(tool == LintTool.DETEKT ? "src/main/kotlin" : "src/main/java");
    }

    /**
     * The module-relative files {@code tool}'s configuration names: a change to any re-runs the
     * step. A Checkstyle rule set, suppressions or header file at a URL is not among them — the
     * engine fetches it as the {@code checkstyle-config} / {@code checkstyle-suppressions} / {@code
     * checkstyle-header} tool and keys the step on its content — and one inside a {@code
     * checkstyle-classpath} jar rides that jar's hash.
     */
    static List<String> configFiles(LintTool tool, PluginConfig config) {
        return switch (tool) {
            case CHECKSTYLE -> {
                List<String> files = new ArrayList<>();
                for (String key : List.of("checkstyle", "checkstyle-suppressions", "checkstyle-header")) {
                    config.stringOpt(key).filter(c -> !isUrl(c)).ifPresent(files::add);
                }
                yield files;
            }
            case PMD -> {
                List<String> files = new ArrayList<>(config.stringList("pmd").stream()
                        .filter(LintPlugin::isFile)
                        .toList());
                config.stringOpt("pmd-exclude").ifPresent(files::add);
                yield files;
            }
            case SPOTBUGS -> config.stringOpt("spotbugs-exclude").map(List::of).orElse(List.of());
            case DETEKT -> config.stringOpt("detekt-config").map(List::of).orElse(List.of());
        };
    }

    /**
     * A PMD ruleset that is a file in the module, as opposed to a built-in {@code category/java/…}
     * or {@code rulesets/…} — Maven's own default among them, which jk carries.
     */
    static boolean isFile(String ruleset) {
        return !ruleset.startsWith("category/") && !ruleset.startsWith("rulesets/");
    }

    /** A configuration at an http(s) URL rather than in the module. */
    static boolean isUrl(String configuration) {
        return configuration.startsWith("https://") || configuration.startsWith("http://");
    }
}
