// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Build profile: javac/JVM args, optional test tag filters, and optional {@code inherits}
 * (parent-then-child merge for args; tag lists are last-wins when the child sets the key).
 * Selected via {@code --profile}; {@code ci} auto-selects in CI.
 *
 * <p>{@link #includeTagsSet()} / {@link #excludeTagsSet()} record whether the TOML key was present
 * so an empty list can clear {@code [test]} filters when the profile is applied.
 */
public record Profile(
        String name,
        @Nullable String inherits,
        List<String> javacArgs,
        List<String> jvmArgs,
        /** JUnit tags to include when this profile is active (meaningful when {@link #includeTagsSet}). */
        List<String> includeTags,
        /** JUnit tags to exclude when this profile is active (meaningful when {@link #excludeTagsSet}). */
        List<String> excludeTags,
        boolean includeTagsSet,
        boolean excludeTagsSet) {

    public Profile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(javacArgs, "javacArgs");
        Objects.requireNonNull(jvmArgs, "jvmArgs");
        javacArgs = List.copyOf(javacArgs);
        jvmArgs = List.copyOf(jvmArgs);
        includeTags = includeTags == null ? List.of() : List.copyOf(includeTags);
        excludeTags = excludeTags == null ? List.of() : List.copyOf(excludeTags);
    }

    /** No tag keys set (profile does not override {@code [test]} tag filters). */
    public Profile(String name, @Nullable String inherits, List<String> javacArgs, List<String> jvmArgs) {
        this(name, inherits, javacArgs, jvmArgs, List.of(), List.of(), false, false);
    }

    /**
     * Both tag keys treated as set (tests / callers that pass explicit lists). Prefer the full
     * constructor with set-flags when empty must mean “clear filters”.
     */
    public Profile(
            String name,
            @Nullable String inherits,
            List<String> javacArgs,
            List<String> jvmArgs,
            List<String> includeTags,
            List<String> excludeTags) {
        this(name, inherits, javacArgs, jvmArgs, includeTags, excludeTags, true, true);
    }

    public static Profile of(String name) {
        return new Profile(name, null, List.of(), List.of());
    }
}
