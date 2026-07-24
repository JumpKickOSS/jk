// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
import java.util.Objects;

/**
 * Build profile: javac/JVM args, optional test tag filters, and optional {@code inherits}
 * (parent-then-child merge). Selected via {@code --profile}; {@code ci} auto-selects in CI.
 */
public record Profile(
        String name,
        String inherits,
        List<String> javacArgs,
        List<String> jvmArgs,
        /** JUnit tags to include when this profile is active (JK-1137). */
        List<String> includeTags,
        /** JUnit tags to exclude when this profile is active (JK-1137). */
        List<String> excludeTags) {

    public Profile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(javacArgs, "javacArgs");
        Objects.requireNonNull(jvmArgs, "jvmArgs");
        javacArgs = List.copyOf(javacArgs);
        jvmArgs = List.copyOf(jvmArgs);
        includeTags = includeTags == null ? List.of() : List.copyOf(includeTags);
        excludeTags = excludeTags == null ? List.of() : List.copyOf(excludeTags);
    }

    /** Backward-compatible: no tag filters. */
    public Profile(String name, String inherits, List<String> javacArgs, List<String> jvmArgs) {
        this(name, inherits, javacArgs, jvmArgs, List.of(), List.of());
    }

    public static Profile of(String name) {
        return new Profile(name, null, List.of(), List.of(), List.of(), List.of());
    }
}
