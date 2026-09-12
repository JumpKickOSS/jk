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
 */
public record JavacConfig(
        Map<String, List<String>> plugins,
        List<String> args,
        @Nullable JavacConfig test) {

    public static final JavacConfig EMPTY = new JavacConfig(Map.of(), List.of(), null);

    public JavacConfig {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        if (plugins != null) plugins.forEach((name, options) -> ordered.put(name, List.copyOf(options)));
        plugins = Collections.unmodifiableMap(ordered);
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** The same plugins and args for both compile steps. */
    public JavacConfig(Map<String, List<String>> plugins, List<String> args) {
        this(plugins, args, null);
    }

    /** What compile-test invokes: the {@code [javac.test]} table when declared, else this one. */
    public JavacConfig forTests() {
        return test == null ? this : test;
    }

    public boolean isEmpty() {
        return plugins.isEmpty() && args.isEmpty();
    }
}
