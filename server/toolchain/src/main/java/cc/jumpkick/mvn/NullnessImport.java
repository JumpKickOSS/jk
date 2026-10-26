// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NullnessConfig;
import cc.jumpkick.model.Scope;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A POM that runs NullAway through Error Prone, and nothing else through it, becomes a
 * {@code [nullness]} table: the plugin argument, its two companion flags and the two processor jars
 * are what the table brings. A POM that runs other Error Prone checks keeps its wiring as written.
 */
final class NullnessImport {

    private NullnessImport() {}

    private static final String PLUGIN_ARG = "-Xplugin:" + NullnessConfig.PLUGIN;
    private static final String OPTION = "-XepOpt:NullAway:";
    private static final List<String> DROPPED_ARGS =
            List.of("-XDcompilePolicy=simple", "--should-stop=ifError=FLOW", "-XDshould-stop.ifError=FLOW");
    private static final List<String> TOOLS =
            List.of("com.google.errorprone:error_prone_core", "com.uber.nullaway:nullaway");

    /** {@code build} with its NullAway wiring as {@code [nullness]}, or {@code build} when it has none to map. */
    static JkBuild map(JkBuild build, ImportReport.Builder report) {
        List<Dependency> processor = build.dependencies().of(Scope.PROCESSOR);
        if (processor.stream().noneMatch(d -> "com.uber.nullaway:nullaway".equals(d.module()))) return build;
        JavacConfig javac = build.build().javac();
        NullnessConfig main = parse(javac.args(), build.project().group());
        if (main == null) return build;
        JavacConfig declaredTest = javac.test();
        boolean tests = declaredTest == null
                || parse(declaredTest.args(), build.project().group()) != null;
        NullnessConfig nullness = new NullnessConfig(main.packages(), main.severity(), main.options(), tests);

        JavacConfig test = declaredTest == null
                ? null
                : new JavacConfig(
                        declaredTest.plugins(), withoutWiring(declaredTest.args()), null, declaredTest.release());
        JavacConfig rest = new JavacConfig(javac.plugins(), withoutWiring(javac.args()), test, null);
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        build.dependencies().byScope().forEach(byScope::put);
        List<Dependency> kept =
                processor.stream().filter(d -> !TOOLS.contains(d.module())).toList();
        if (kept.isEmpty()) byScope.remove(Scope.PROCESSOR);
        else byScope.put(Scope.PROCESSOR, kept);
        report.warning("NullAway runs through Error Prone here, so the import writes `[nullness]`: the table brings"
                + " Error Prone, NullAway and JSpecify at jk's versions and the flags they need, in place of the"
                + " POM's `-Xplugin:ErrorProne` argument and its processor paths.");
        return build.withDependencies(new JkBuild.Dependencies(byScope))
                .withBuild(build.build().withJavac(rest).withNullness(nullness));
    }

    /**
     * The table one compile's {@code args} spell, or {@code null} when they run no Error Prone plugin
     * or run checks beside NullAway.
     */
    private static @Nullable NullnessConfig parse(List<String> args, String group) {
        String plugin = null;
        for (String arg : args) {
            if (arg.equals(PLUGIN_ARG) || arg.startsWith(PLUGIN_ARG + " ")) plugin = arg;
        }
        if (plugin == null) return null;
        List<String> packages = null;
        NullnessConfig.Severity severity = null;
        List<String> options = new ArrayList<>();
        for (String token :
                Arrays.asList(plugin.substring(PLUGIN_ARG.length()).trim().split("\\s+"))) {
            if (token.isEmpty() || token.equals("-XepDisableAllChecks")) continue;
            if (token.startsWith("-Xep:NullAway")) {
                // A bare -Xep:NullAway is the check at its own default, a warning.
                String level = token.startsWith("-Xep:NullAway:") ? token.substring("-Xep:NullAway:".length()) : "WARN";
                severity = switch (level) {
                    case "ERROR" -> NullnessConfig.Severity.ERROR;
                    case "WARN" -> NullnessConfig.Severity.WARN;
                    default -> null;
                };
                if (severity == null) return null;
            } else if (token.startsWith(OPTION + "AnnotatedPackages=")) {
                packages = List.of(token.substring((OPTION + "AnnotatedPackages=").length())
                        .split(","));
            } else if (token.equals(OPTION + "OnlyNullMarked=true")) {
                packages = List.of();
            } else if (token.equals(OPTION + "JSpecifyMode=true")) {
                continue;
            } else if (token.startsWith(OPTION)) {
                options.add(token);
            } else {
                return null;
            }
        }
        if (severity == null) return null;
        if (packages != null && packages.equals(List.of(group))) packages = null;
        return new NullnessConfig(packages, severity, options, false);
    }

    private static List<String> withoutWiring(List<String> args) {
        return args.stream()
                .filter(a -> !DROPPED_ARGS.contains(a))
                .filter(a -> !a.equals(PLUGIN_ARG) && !a.startsWith(PLUGIN_ARG + " "))
                .toList();
    }
}
