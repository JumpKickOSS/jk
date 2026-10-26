// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code [nullness]}: null checking by NullAway, which runs as an Error Prone check, over JSpecify
 * annotations. The table brings the three jars itself; {@link #fold} lowers it into the
 * {@code [javac]} the compiles run.
 *
 * <p>{@code packages} are the packages NullAway treats as annotated: {@code null} means the module's
 * group, and empty means only code under {@code @NullMarked}. {@code options} are further Error
 * Prone flags, passed after the table's own. {@code tests} checks compile-test too.
 */
public record NullnessConfig(@Nullable List<String> packages, Severity severity, List<String> options, boolean tests) {

    /** The severity NullAway's findings carry; {@code ERROR} fails the compile. */
    public enum Severity {
        ERROR,
        WARN;

        /** The spelling in {@code jk.toml}. */
        public String toml() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The javac plugin NullAway runs inside, by its registered name. */
    public static final String PLUGIN = "ErrorProne";

    /** The javac flags Error Prone documents beside its plugin. */
    public static final List<String> COMPANION_ARGS = List.of("-XDcompilePolicy=simple", "--should-stop=ifError=FLOW");

    /** The table as {@code [nullness]} alone writes it: the group's packages, at error severity. */
    public static final NullnessConfig DEFAULT = new NullnessConfig(null, Severity.ERROR, List.of(), false);

    public NullnessConfig {
        packages = packages == null ? null : List.copyOf(packages);
        severity = severity == null ? Severity.ERROR : severity;
        options = options == null ? List.of() : List.copyOf(options);
    }

    /**
     * {@code javac} with this table folded in. A module that names no Error Prone plugin gets one
     * running NullAway alone; one that does keeps its checks and gains NullAway's. compile-test
     * gets the same unless {@code tests} is false, when it runs {@code javac} as written.
     *
     * @param group the module's group, the default annotated package
     * @throws IllegalArgumentException when {@code packages} is unset and the module has no group
     */
    public JavacConfig fold(JavacConfig javac, @Nullable String group) {
        List<String> checks = checks(group);
        JavacConfig declaredTest = javac.test();
        JavacConfig test;
        if (tests) {
            test = declaredTest == null ? null : with(declaredTest, checks, null);
        } else {
            test = declaredTest != null ? declaredTest : new JavacConfig(javac.plugins(), javac.args());
        }
        return with(javac, checks, test);
    }

    /** The Error Prone flags that turn NullAway on. */
    List<String> checks(@Nullable String group) {
        List<String> out = new ArrayList<>();
        out.add("-Xep:NullAway:" + severity.name());
        out.add("-XepOpt:NullAway:JSpecifyMode=true");
        List<String> annotated = packages;
        if (annotated == null) {
            if (group == null || group.isBlank()) {
                throw new IllegalArgumentException("[nullness] checks the module's group by default, and this module"
                        + " has none: name the packages, packages = [\"com.example\"], or check only @NullMarked"
                        + " code with packages = []");
            }
            annotated = List.of(group);
        }
        out.add(
                annotated.isEmpty()
                        ? "-XepOpt:NullAway:OnlyNullMarked=true"
                        : "-XepOpt:NullAway:AnnotatedPackages=" + String.join(",", annotated));
        out.addAll(options);
        return out;
    }

    private static JavacConfig with(JavacConfig javac, List<String> checks, @Nullable JavacConfig test) {
        Map<String, List<String>> plugins = new LinkedHashMap<>(javac.plugins());
        List<String> declared = plugins.get(PLUGIN);
        List<String> options = new ArrayList<>();
        if (declared == null) {
            // NullAway alone: Error Prone's own checks are a separate choice the module has not made.
            options.add("-XepDisableAllChecks");
        } else {
            options.addAll(declared);
        }
        for (String check : checks) {
            if (!options.contains(check)) options.add(check);
        }
        plugins.put(PLUGIN, options);
        List<String> args = new ArrayList<>(javac.args());
        for (String arg : COMPANION_ARGS) {
            if (!args.contains(arg)) args.add(arg);
        }
        return new JavacConfig(plugins, args, test, javac.release());
    }
}
