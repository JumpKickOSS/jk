// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The read-only project view a plugin sees (build-plugins plan §3.1 {@code ProjectView}): display
 * coordinates, the resolved entry point, and capability facts — never CAS paths, never jk
 * directory-layout knowledge.
 *
 * @param mainClass the resolved application entry point (CLI override &gt; declared &gt; unique
 *     main scan), or null when the project declares none and none is discoverable
 * @param nativeDeclared the project declares a {@code [native]} table
 * @param manifest extra {@code [manifest]} attributes destined for the packaged artifact
 */
public record ProjectFacts(
        String group,
        String name,
        String version,
        int javaRelease,
        @Nullable String mainClass,
        boolean nativeDeclared,
        boolean kotlin,
        Map<String, String> manifest) {

    public ProjectFacts {
        manifest = manifest.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(manifest));
    }

    /**
     * The stable render every action key hashes this fact set through — the one place facts become
     * a cache key. Every component participates, deliberately: a fact that reaches a plugin body
     * but not its key restores a stale artifact, so a new component must be rendered here in the
     * same change (a test pins the component count so it cannot be forgotten).
     *
     * <p>{@code manifest} renders in declaration order rather than sorted — the packaged {@code
     * MANIFEST.MF} carries that order, so a reorder really is a different artifact.
     */
    public String token() {
        StringBuilder b = new StringBuilder(group + ':' + name + ':' + version
                + "|release=" + javaRelease
                + "|main=" + (mainClass == null ? "" : mainClass)
                + "|native=" + nativeDeclared
                + "|kotlin=" + kotlin);
        for (Map.Entry<String, String> attribute : manifest.entrySet()) {
            b.append("|manifest.").append(attribute.getKey()).append('=').append(attribute.getValue());
        }
        return b.toString();
    }
}
