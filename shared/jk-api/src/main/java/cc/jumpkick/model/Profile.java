// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Build profile: javac and test JVM args, test JVM environment, and an optional {@code inherits}
 * (parent-then-child merge). Selected with {@code --profile}. A profile chooses how a build runs,
 * never which tests: suites do that ({@code [test.suites.<name>]}, {@code jk test -s <name>}).
 */
public record Profile(
        String name,
        @Nullable String inherits,
        List<String> javacArgs,
        List<String> jvmArgs,
        /** Environment for every forked test JVM while this profile is active; wins over {@code [test] env}. */
        Map<String, String> env) {

    public Profile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(javacArgs, "javacArgs");
        Objects.requireNonNull(jvmArgs, "jvmArgs");
        javacArgs = List.copyOf(javacArgs);
        jvmArgs = List.copyOf(jvmArgs);
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    /** No test environment of its own. */
    public Profile(String name, @Nullable String inherits, List<String> javacArgs, List<String> jvmArgs) {
        this(name, inherits, javacArgs, jvmArgs, Map.of());
    }

    public static Profile of(String name) {
        return new Profile(name, null, List.of(), List.of());
    }
}
