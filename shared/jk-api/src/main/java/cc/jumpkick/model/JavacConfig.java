// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code [javac]}: {@code plugins} maps a javac plugin's registered name (the {@code -Xplugin:}
 * argument, case-sensitive: {@code ErrorProne}) to its options, in manifest order; {@code args}
 * are verbatim javac arguments appended after every plugin. The plugin jars themselves come
 * from {@code [processor-dependencies]} — javac looks plugins up on the processor path.
 *
 * <p>{@code test} is the {@code [javac.test]} table: when present it replaces this one for
 * compile-test, so a suite that hands null to parsers on purpose can run without the checks its
 * production code is held to. Absent, compile-test runs the same plugins and args as compile-main.
 *
 * <p>{@code release} is {@code [javac.test] release}, the one key the test table has and the main
 * table has not: the {@code --release} compile-test targets when it is to be above the module's
 * {@code java} level — a JDK 17 library whose suite uses a JDK 21 API. Null on the main table
 * always (the module's level is {@code java = N}), and null on the test table when the suite is
 * compiled at the module's level.
 */
public record JavacConfig(
        Map<String, List<String>> plugins,
        List<String> args,
        @Nullable JavacConfig test,
        @Nullable Integer release) {

    public static final JavacConfig EMPTY = new JavacConfig(Map.of(), List.of(), null, null);

    public JavacConfig {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        if (plugins != null) plugins.forEach((name, options) -> ordered.put(name, List.copyOf(options)));
        plugins = Collections.unmodifiableMap(ordered);
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** The main table with a test view and no test-only release. */
    public JavacConfig(Map<String, List<String>> plugins, List<String> args, @Nullable JavacConfig test) {
        this(plugins, args, test, null);
    }

    /** The same plugins and args for both compile steps. */
    public JavacConfig(Map<String, List<String>> plugins, List<String> args) {
        this(plugins, args, null, null);
    }

    /** What compile-test invokes: the {@code [javac.test]} table when declared, else this one. */
    public JavacConfig forTests() {
        return test == null ? this : test;
    }

    /**
     * The {@code --release} compile-test targets: {@code [javac.test] release} when declared, else
     * {@code moduleRelease}, the module's own {@code java} level. A declared release below the
     * module's is refused — the key exists to let a suite use a newer API than the library it
     * tests, never to compile tests for an older runtime than the code they exercise.
     */
    public int testRelease(int moduleRelease) {
        Integer declared = test == null ? null : test.release;
        if (declared == null) return moduleRelease;
        if (declared < moduleRelease) {
            throw new IllegalArgumentException("[javac.test] release = " + declared + " is below the module's java = "
                    + moduleRelease + " — a test-only release can only raise the level");
        }
        return declared;
    }

    public boolean isEmpty() {
        return plugins.isEmpty() && args.isEmpty();
    }
}
