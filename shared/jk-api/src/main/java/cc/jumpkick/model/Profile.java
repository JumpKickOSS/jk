// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
import java.util.Objects;

/**
 * Build profile: javac/JVM args and optional {@code inherits} (parent-then-child merge). Changes
 * how code is built/run, not what. Selected via {@code --profile}; {@code ci} auto-selects in CI.
 */
public record Profile(String name, String inherits, List<String> javacArgs, List<String> jvmArgs) {

    public Profile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(javacArgs, "javacArgs");
        Objects.requireNonNull(jvmArgs, "jvmArgs");
        javacArgs = List.copyOf(javacArgs);
        jvmArgs = List.copyOf(jvmArgs);
    }

    public static Profile of(String name) {
        return new Profile(name, null, List.of(), List.of());
    }
}
