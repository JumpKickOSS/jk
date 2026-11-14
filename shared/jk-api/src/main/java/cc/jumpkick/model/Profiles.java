// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    public static Profiles empty() {
        return new Profiles(Map.of());
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    /**
     * Walk the inherits chain and merge — javac/JVM args: parent first, child appends; env: a child
     * entry replaces the parent's of the same name. Cycle in the chain → {@link IllegalStateException}.
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
        Map<String, String> mergedEnv = new LinkedHashMap<>(parent.env());
        mergedEnv.putAll(current.env());
        return new Profile(name, null, mergedJavac, mergedJvm, mergedEnv);
    }

    /** The profile {@code requested} names outright: null when blank or absent. */
    public static @Nullable String named(@Nullable String requested) {
        return requested == null || requested.isBlank() ? null : requested;
    }
}
