// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import cc.jumpkick.config.EnvValues;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The {@code profiles} block of a {@code jk.toml}. Holds the raw {@link Profile}s by name and
 * resolves {@code inherits} chains.
 */
public record Profiles(Map<String, Profile> byName) {

    public Profiles {
        Objects.requireNonNull(byName, "byName");
        byName = Map.copyOf(byName);
    }

    /**
     * The profile a run names when it asked for none ({@code --no-profile}): neither the named nor the
     * CI-selected profile applies. It rides the request's profile field, where {@code null} already
     * means "select automatically", and is no name a manifest can declare as a bare key.
     */
    public static final String NO_PROFILE = "<none>";

    public static Profiles empty() {
        return new Profiles(Map.of());
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    /**
     * Walk the inherits chain and merge — javac/JVM args: parent first, child appends; env: a child
     * entry replaces the parent's of the same name. Tag lists:
     * child replaces parent when the child sets that key; otherwise inherit the parent's list and
     * set-flag. {@code all-suites}: set when either sets it. Cycle in the chain → {@link IllegalStateException}.
     */
    public Profile resolve(String name) {
        if (!byName.containsKey(name)) {
            throw new IllegalArgumentException("no profile named `" + name + "`");
        }
        return resolveInternal(name, new HashSet<>());
    }

    private Profile resolveInternal(String name, Set<String> visiting) {
        if (!visiting.add(name)) {
            throw new IllegalStateException("cycle in profile inheritance at `" + name + "` (chain: " + visiting + ")");
        }
        Profile current = Objects.requireNonNull(byName.get(name));
        if (current.inherits() == null) {
            return current;
        }
        Profile parent = resolveInternal(current.inherits(), visiting);
        List<String> mergedJavac = new ArrayList<>(parent.javacArgs());
        mergedJavac.addAll(current.javacArgs());
        List<String> mergedJvm = new ArrayList<>(parent.jvmArgs());
        mergedJvm.addAll(current.jvmArgs());
        boolean includeSet = current.includeTagsSet() || parent.includeTagsSet();
        List<String> include = current.includeTagsSet() ? current.includeTags() : parent.includeTags();
        boolean excludeSet = current.excludeTagsSet() || parent.excludeTagsSet();
        List<String> exclude = current.excludeTagsSet() ? current.excludeTags() : parent.excludeTags();
        Map<String, String> mergedEnv = new LinkedHashMap<>(parent.env());
        mergedEnv.putAll(current.env());
        return new Profile(
                name,
                null,
                mergedJavac,
                mergedJvm,
                include,
                exclude,
                includeSet,
                excludeSet,
                mergedEnv,
                current.allSuites() || parent.allSuites());
    }

    /**
     * The profile a run applies: none under {@link #NO_PROFILE}, {@code requested} when it names one,
     * else the one {@link #autoSelect} picks from {@code env}.
     */
    public static @Nullable String active(@Nullable String requested, Function<String, @Nullable String> env) {
        if (NO_PROFILE.equals(requested)) return null;
        if (requested != null && !requested.isBlank()) return requested;
        return autoSelect(env);
    }

    /** The profile {@code requested} names outright: null when blank, absent or {@link #NO_PROFILE}. */
    public static @Nullable String named(@Nullable String requested) {
        return requested == null || requested.isBlank() || NO_PROFILE.equals(requested) ? null : requested;
    }

    /** Picks the auto-selected profile name based on env, or {@code null}. */
    public static @Nullable String autoSelect(Function<String, @Nullable String> env) {
        return EnvValues.isCi(env) || env.apply("GITHUB_ACTIONS") != null || env.apply("GITLAB_CI") != null
                ? "ci"
                : null;
    }
}
