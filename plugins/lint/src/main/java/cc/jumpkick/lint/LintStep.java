// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.DeterministicProperties;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * The lint step's body: fork {@code java -cp <tool closure> <main> <args>} on the build JDK with
 * the tool's XML report as its output, read the report back as {@link Finding}s, report each as a
 * diagnostic with its rule id, and fail the step when a finding reaches the tool's threshold —
 * its own {@code <tool>-fail-on}, else the table's {@code fail-on}. A tool that wrote no report
 * failed on its own terms: the step fails with the tool's last lines. Checkstyle's classpath is
 * its closure plus the {@code checkstyle-classpath} jars, so a rule set, header or suppressions
 * file may be a resource inside one of them, named as Checkstyle resolves it.
 */
final class LintStep {

    /** How many trailing output lines a failure message carries. */
    private static final int TAIL = 40;

    /** PMD's exit for violations found ({@code --no-fail-on-violation} keeps it at zero) and for recoverable errors. */
    private static final Set<Integer> PMD_RAN = Set.of(0, 4, 5);

    /**
     * The ruleset {@code maven-pmd-plugin} runs when a POM names none, by the name Maven gives it.
     * PMD ships no such ruleset, so the step hands PMD the copy jk carries.
     */
    static final String MAVEN_PMD_DEFAULT = "rulesets/java/maven-pmd-plugin-default.xml";

    private LintStep() {}

    static void run(TaskExec exec, LintTool tool) throws Exception {
        run(exec, tool, null);
    }

    /**
     * {@link #run(TaskExec, LintTool)} for the table's own tool ({@code run} null) or for one {@code
     * [lint.<run>]} Checkstyle run, whose configuration is the entry's ({@link LintPlugin#scoped})
     * and whose tool, rule set, classpath, suppressions and header extras carry the run's name.
     */
    static void run(TaskExec exec, LintTool tool, @Nullable String run) throws Exception {
        PluginConfig config = run == null ? exec.config() : LintPlugin.scoped(exec.config(), run);
        String label = tool.id() + (run == null ? "" : ":" + run);
        if (tool == LintTool.SPOTBUGS) {
            config.stringOpt("spotbugs-version").ifPresent(v -> SpotBugsFloor.check(v, exec.javaHome()));
        }
        List<Path> roots = existingRoots(exec.moduleDir(), LintPlugin.sourceRoots(tool, config));
        Path out = exec.outputDir(tool.out(run));
        Path report = out.resolve(tool.report());
        Files.deleteIfExists(report);
        if (tool != LintTool.SPOTBUGS && !hasFiles(tool, roots, config)) {
            exec.label(label + " (no sources)");
            Files.writeString(report, "");
            return;
        }
        List<Path> classpath = new ArrayList<>(toolClasspath(exec.requireExtra(extra(tool.id(), run))));
        if (tool == LintTool.CHECKSTYLE) {
            Optional<Path> jars = exec.extra(extra("checkstyle-classpath", run));
            if (jars.isPresent()) classpath.addAll(toolClasspath(jars.get()));
        }
        String missing = missingConfiguration(tool, exec.moduleDir(), config, classpath);
        if (missing != null) {
            exec.diagnostic(
                    Finding.WARNING,
                    null,
                    0,
                    0,
                    label + ": `" + missing + "` is not a file in the module, so nothing was linted — copy the rule"
                            + " set to that path, or point `[lint] " + tool.id() + "` at the file that holds it"
                            + (tool == LintTool.CHECKSTYLE
                                    ? " (a resource of a jar is one when `checkstyle-classpath` names the jar)"
                                    : ""));
            exec.label(label + " (no configuration)");
            Files.writeString(report, "");
            return;
        }
        List<String> args = arguments(tool, exec, config, roots, report, run);
        exec.label(label + " (" + roots.size() + (roots.size() == 1 ? " root" : " roots") + ")");
        List<String> output = new ArrayList<>();
        int exit = exec.java().classpath(classpath).mainClass(tool.main()).args(args).cwd(exec.moduleDir()).stream(
                output::add);
        if (!Files.isRegularFile(report) || !ran(tool, exit)) {
            List<String> tail = output.subList(Math.max(0, output.size() - TAIL), output.size());
            throw new IllegalStateException(label + " failed (exit " + exit + ") before writing its report"
                    + (tail.isEmpty() ? "" : ":\n" + String.join("\n", tail)));
        }
        List<Finding> findings = Reports.parse(tool, report, roots, exclusions(tool, exec));
        String failOnKey = config.stringOpt(tool.id() + "-fail-on").isPresent() ? tool.id() + "-fail-on" : "fail-on";
        String failOn = config.stringOpt(failOnKey).orElse(Finding.ERROR).toLowerCase(Locale.ROOT);
        int errors = 0;
        for (Finding finding : findings) {
            exec.diagnostic(finding.severity(), finding.file(), finding.line(), finding.col(), finding.text());
            if (fails(finding, failOn)) errors++;
        }
        if (errors > 0) {
            throw new IllegalStateException(label + ": " + errors + (errors == 1 ? " finding" : " findings")
                    + " at or above `" + failOnKey + " = \"" + failOn + "\"` (" + findings.size() + " in all)");
        }
    }

    /** The name an extra is handed to the step under: the table's own, or the run's with the run's name after it. */
    static String extra(String artifact, @Nullable String run) {
        return run == null ? artifact : artifact + "-" + run;
    }

    /** Whether {@code finding} fails the step under {@code failOn}: error, warning or never. */
    static boolean fails(Finding finding, String failOn) {
        return switch (failOn) {
            case "never" -> false;
            case "warning" -> true;
            default -> finding.isError();
        };
    }

    /** Whether {@code exit} is one the tool uses after a completed analysis — findings included. */
    static boolean ran(LintTool tool, int exit) {
        return switch (tool) {
            case CHECKSTYLE -> true; // the exit is the error count
            case PMD -> PMD_RAN.contains(exit);
            case SPOTBUGS -> (exit & 4) == 0; // bit 1 bugs found, bit 2 classes missing, bit 4 an error
            case DETEKT -> exit == 0 || exit == 2; // 2: the configured issue budget is spent
        };
    }

    /** The tool's command line for the table's own run, its report at {@code report}. */
    static List<String> arguments(LintTool tool, TaskExec exec, List<Path> roots, Path report) {
        return arguments(tool, exec, exec.config(), roots, report, null);
    }

    /** The tool's command line under {@code config}, its report at {@code report}; {@code run} names a Checkstyle run's extras. */
    static List<String> arguments(
            LintTool tool, TaskExec exec, PluginConfig config, List<Path> roots, Path report, @Nullable String run) {
        Path module = exec.moduleDir();
        List<String> args = new ArrayList<>();
        switch (tool) {
            case CHECKSTYLE -> {
                args.addAll(
                        List.of("-c", checkstyleLocation(exec, config, "checkstyle", extra("checkstyle-config", run))));
                Map<String, String> properties = new LinkedHashMap<>(config.stringMap("checkstyle-properties"));
                if (config.stringOpt("checkstyle-suppressions").isPresent()) {
                    properties.put(
                            "checkstyle.suppressions.file",
                            checkstyleLocation(
                                    exec, config, "checkstyle-suppressions", extra("checkstyle-suppressions", run)));
                }
                if (config.stringOpt("checkstyle-header").isPresent()) {
                    properties.put(
                            "checkstyle.header.file",
                            checkstyleLocation(exec, config, "checkstyle-header", extra("checkstyle-header", run)));
                }
                if (!properties.isEmpty()) {
                    args.addAll(List.of(
                            "-p", checkstyleProperties(report, properties).toString()));
                }
                args.addAll(List.of("-f", "xml", "-o", report.toString()));
                for (String glob : config.stringList("exclude")) args.addAll(List.of("-x", excludeRegex(glob)));
                for (Path root : roots) args.add(root.toString());
            }
            case PMD -> {
                args.addAll(List.of("check", "--no-cache", "--no-progress", "--no-fail-on-violation"));
                args.addAll(List.of("--format", "xml", "--report-file", report.toString()));
                for (Path root : roots) args.addAll(List.of("--dir", root.toString()));
                List<String> rulesets = new ArrayList<>();
                for (String ruleset : config.stringList("pmd")) {
                    if (ruleset.equals(MAVEN_PMD_DEFAULT)) {
                        Path out =
                                Objects.requireNonNull(report.toAbsolutePath().getParent(), "report dir");
                        rulesets.add(bundledMavenDefault(out).toString());
                    } else {
                        rulesets.add(
                                LintPlugin.isFile(ruleset)
                                        ? module.resolve(ruleset).toString()
                                        : ruleset);
                    }
                }
                args.addAll(List.of("--rulesets", String.join(",", rulesets)));
            }
            case SPOTBUGS -> {
                args.addAll(List.of("-xml:withMessages", "-output", report.toString()));
                // The confidence floor, as SpotBugs spells it (-low, -medium, -high); medium is
                // SpotBugs's own default and the Maven plugin's.
                args.add("-" + config.stringOpt("spotbugs-threshold").orElse("medium"));
                args.add("-effort:" + config.stringOpt("spotbugs-effort").orElse("default"));
                if (!roots.isEmpty()) args.addAll(List.of("-sourcepath", Classpaths.join(roots)));
                List<Path> aux = exec.compileClasspath();
                if (!aux.isEmpty()) args.addAll(List.of("-auxclasspath", Classpaths.join(aux)));
                config.stringOpt("spotbugs-exclude")
                        .ifPresent(exclude -> args.addAll(
                                List.of("-exclude", module.resolve(exclude).toString())));
                args.add(exec.classesDir().toString());
            }
            case DETEKT -> {
                args.addAll(List.of("--input", commaJoined(roots), "--report", "xml:" + report));
                List<String> excludes = config.stringList("exclude");
                if (!excludes.isEmpty()) args.addAll(List.of("--excludes", String.join(",", excludes)));
                config.stringOpt("detekt-config")
                        .ifPresent(cfg -> args.addAll(
                                List.of("--config", module.resolve(cfg).toString(), "--build-upon-default-config")));
            }
        }
        return args;
    }

    /**
     * The location {@code config} names under {@code key}, as Checkstyle is handed it: the
     * engine-fetched {@code extra} for a URL, the module's file when the module holds it, else the
     * value as written — a resource Checkstyle finds on its classpath.
     */
    private static String checkstyleLocation(TaskExec exec, PluginConfig config, String key, String extra) {
        String configured = config.string(key);
        if (LintPlugin.isUrl(configured)) return exec.requireExtra(extra).toString();
        Path file = exec.moduleDir().resolve(configured);
        return Files.isRegularFile(file) ? file.toString() : configured;
    }

    /**
     * Checkstyle's {@code -p} file beside {@code report}, defining {@code properties}: {@code
     * checkstyle.suppressions.file} and {@code checkstyle.header.file} — the properties {@code
     * maven-checkstyle-plugin} binds {@code <suppressionsLocation>} and {@code <headerLocation>} to,
     * which a rule set's {@code SuppressionFilter} and {@code Header} read — and every {@code
     * checkstyle-properties} entry, as {@code <propertyExpansion>} hands them over.
     */
    static Path checkstyleProperties(Path report, Map<String, String> properties) {
        Path out = Objects.requireNonNull(report.toAbsolutePath().getParent(), "report dir");
        Path file = out.resolve("checkstyle.properties");
        try {
            Files.createDirectories(out);
            Files.writeString(file, DeterministicProperties.render(properties), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /** jk's copy of Maven's default ruleset, written under {@code out} for PMD to read as a file. */
    static Path bundledMavenDefault(Path out) {
        Path file = out.resolve("maven-pmd-plugin-default.xml");
        try (InputStream in = LintStep.class.getResourceAsStream("maven-pmd-plugin-default.xml")) {
            Files.createDirectories(out);
            Files.copy(Objects.requireNonNull(in, "bundled maven-pmd-plugin-default.xml"), file, REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /** The module's {@code pmd-exclude} file read, or nothing left out; other tools have no such file. */
    static PmdExclusions exclusions(LintTool tool, TaskExec exec) throws IOException {
        if (tool != LintTool.PMD) return PmdExclusions.NONE;
        Optional<String> file = exec.config().stringOpt("pmd-exclude");
        if (file.isEmpty()) return PmdExclusions.NONE;
        Path path = exec.moduleDir().resolve(file.get());
        if (!Files.isRegularFile(path)) {
            exec.diagnostic(
                    Finding.WARNING,
                    null,
                    0,
                    0,
                    "pmd: `" + file.get() + "` (`[lint] pmd-exclude`) is not a file in the module; nothing is left out"
                            + " of the report");
            return PmdExclusions.NONE;
        }
        return PmdExclusions.read(path);
    }

    /**
     * A module-relative Ant-style path glob ({@code **}{@code /generated/**}) as the regular
     * expression Checkstyle's {@code -x} finds in a file's absolute path: {@code **} spans
     * directories, {@code *} and {@code ?} stay within one name, and the match starts at a name
     * boundary and, unless the glob ends open, ends with the path.
     */
    static String excludeRegex(String glob) {
        String g = glob.replace('\\', '/');
        if (g.startsWith("**/")) g = g.substring(3);
        StringBuilder re = new StringBuilder("(?:^|/)");
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
                re.append(".*");
                i++;
            } else if (c == '*') {
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else if ("\\.[]{}()<>+-=!^$|".indexOf(c) >= 0) {
                re.append('\\').append(c);
            } else {
                re.append(c);
            }
        }
        if (!g.endsWith("**")) re.append('$');
        return re.toString();
    }

    /** detekt's list form: paths separated by commas. */
    private static String commaJoined(List<Path> roots) {
        List<String> names = new ArrayList<>();
        for (Path root : roots) names.add(root.toString());
        return String.join(",", names);
    }

    /**
     * Whether {@code roots} hold a file for {@code tool} to read: Checkstyle refuses an empty file
     * set, so its {@code exclude} globs are applied here the way its {@code -x} applies them, and a
     * module whose every source they cover has nothing to lint.
     */
    static boolean hasFiles(LintTool tool, List<Path> roots, PluginConfig config) throws IOException {
        if (roots.isEmpty()) return false;
        if (tool != LintTool.CHECKSTYLE) return true;
        List<Pattern> excludes = new ArrayList<>();
        for (String glob : config.stringList("exclude")) excludes.add(Pattern.compile(excludeRegex(glob)));
        boolean[] found = {false};
        for (Path root : roots) {
            if (found[0]) break;
            PathUtil.forEachRegularFile(root, (file, attrs) -> {
                if (found[0]) return;
                String path = file.toAbsolutePath().toString().replace('\\', '/');
                if (excludes.stream().noneMatch(p -> p.matcher(path).find())) found[0] = true;
            });
        }
        return found[0];
    }

    /**
     * The configured file {@code tool} cannot run without when the module does not hold it — the
     * path {@code jk import} writes for a rule set it could not carry — or null when it is there,
     * the tool needs none, the engine fetched it from a URL, or a jar on {@code classpath} holds it
     * as a resource.
     */
    static @Nullable String missingConfiguration(LintTool tool, Path module, PluginConfig config, List<Path> classpath)
            throws IOException {
        Optional<String> configured =
                switch (tool) {
                    case CHECKSTYLE -> config.stringOpt("checkstyle").filter(c -> !LintPlugin.isUrl(c));
                    case DETEKT -> config.stringOpt("detekt-config");
                    case PMD, SPOTBUGS -> Optional.empty();
                };
        if (configured.isEmpty() || Files.isRegularFile(module.resolve(configured.get()))) return null;
        if (tool == LintTool.CHECKSTYLE && onClasspath(configured.get(), classpath)) return null;
        return configured.get();
    }

    /** Whether a jar of {@code classpath} holds {@code resource} — a rule set a build ships in a jar. */
    static boolean onClasspath(String resource, List<Path> classpath) throws IOException {
        String name = resource.startsWith("/") ? resource.substring(1) : resource;
        for (Path jar : classpath) {
            if (!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")) continue;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                if (zip.getEntry(name) != null) return true;
            }
        }
        return false;
    }

    /** The declared source roots that exist under the module, absolute. */
    static List<Path> existingRoots(Path module, List<String> roots) {
        List<Path> existing = new ArrayList<>();
        for (String root : roots) {
            Path dir = module.resolve(root).toAbsolutePath().normalize();
            if (Files.isDirectory(dir)) existing.add(dir);
        }
        return existing;
    }

    /** The fetched tool as a classpath: the jar itself, or every jar of a materialized closure dir. */
    static List<Path> toolClasspath(Path tool) throws IOException {
        if (!Files.isDirectory(tool)) return List.of(tool);
        List<Path> jars = new ArrayList<>();
        PathUtil.forEachRegularFile(tool, (file, attrs) -> {
            if (file.getFileName().toString().endsWith(".jar")) jars.add(file);
        });
        jars.sort(null);
        return List.copyOf(jars);
    }
}
