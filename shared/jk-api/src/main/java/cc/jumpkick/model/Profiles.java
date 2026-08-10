// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code profiles} block of a {@code jk.toml}. Holds the raw {@link Profile}s by name and
 * resolves {@code inherits} chains.
 */
public record Profiles(Map<String, Profile> byName) {

    public Profiles {
        Objects.requireNonNull(byName, "byName");
        byName = Map.copyOf(byName);
    }

    public static Profiles empty() {
        return new Profiles(Map.of());
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    /**
     * Walk the inherits chain and merge — javac/JVM args: parent first, child appends. Tag lists:
     * child replaces parent when the child sets that key; otherwise inherit the parent's list and
     * set-flag. Cycle in the chain → {@link IllegalStateException}.
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
        Profile current = byName.get(name);
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
        return new Profile(name, null, mergedJavac, mergedJvm, include, exclude, includeSet, excludeSet);
    }

    /** Picks the auto-selected profile name based on env, or {@code null}. */
    public static String autoSelect(Map<String, String> env) {
        return env.getOrDefault("CI", "").equalsIgnoreCase("true")
                        || env.containsKey("GITHUB_ACTIONS")
                        || env.containsKey("GITLAB_CI")
                ? "ci"
                : null;
    }
}
