// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Resolve a platform BOM {@link VersionSelector} to a concrete release version.
 *
 * <p>The managed catalog is loaded from the <em>resolved</em> BOM POM — so {@code version = "4"}
 * must pick the highest stable 4.x, and {@code latest} the highest stable advertised, before
 * reading {@code dependencyManagement}.
 *
 * <p>Takes a parsed {@link VersionSelector}, never a raw string: whether a <em>bare</em> version
 * means "exact" or "caret floor" is the caller's convention, not this class's. {@code jk.toml}
 * dependencies are bare-is-caret ({@link VersionSelector#parseFloating}); {@code jk-plugin.toml}
 * tool coordinates are bare-is-exact ({@link VersionSelector#parse}) so a plugin author's literal
 * pin stays pinned.
 */
public final class PlatformBomVersions {

    private PlatformBomVersions() {}

    /**
     * Concrete version of {@code group:artifact} satisfying {@code selector}.
     *
     * <ul>
     *   <li>{@link VersionSelector.Exact} → that version (no metadata required).
     *   <li>{@link VersionSelector.Latest} → highest <em>stable</em> version metadata advertises.
     *   <li>{@link VersionSelector.Snapshot} → highest advertised version, pre-releases included.
     *   <li>{@link VersionSelector.Caret}/{@link VersionSelector.Tilde} → highest <em>stable</em>
     *       version in the selector's range that metadata advertises; falls back to the anchor if
     *       metadata is empty (offline / first-publish) when the anchor itself is in range.
     *   <li>Open ranges → rejected.
     * </ul>
     */
    public static String resolve(RepoGroup repos, String group, String artifact, VersionSelector selector)
            throws IOException, InterruptedException {
        Objects.requireNonNull(repos, "repos");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(selector, "selector");

        if (selector instanceof VersionSelector.Exact e) {
            return e.version();
        }

        boolean preferPreRelease = selector instanceof VersionSelector.Snapshot;
        String anchor =
                switch (selector) {
                    case VersionSelector.Caret c -> c.version();
                    case VersionSelector.Tilde t -> t.version();
                    case VersionSelector.Latest ignored -> null;
                    case VersionSelector.Snapshot ignored -> null;
                    case VersionSelector.Exact ignored -> throw new IllegalStateException("unreachable");
                    case VersionSelector.Range r -> throw reject(group, artifact, r.raw());
                };

        VersionSet allowed = VersionSelectors.toVersionSet(selector);
        Coordinate probe = Coordinate.of(group, artifact, anchor != null ? anchor : "0");
        List<String> available = repos.availableVersions(probe);

        String bestStable = null;
        String bestAny = null;
        for (String v : available) {
            if (!allowed.contains(v)) continue;
            if (bestAny == null || Versions.compare(v, bestAny) > 0) bestAny = v;
            if (!Versions.isStable(v)) continue;
            if (bestStable == null || Versions.compare(v, bestStable) > 0) bestStable = v;
        }
        if (preferPreRelease && bestAny != null) return bestAny;
        if (bestStable != null) return bestStable;
        // Anchor is always preferred over an unstable-only catalog when it is in range.
        if (anchor != null && allowed.contains(anchor)) return anchor;
        if (bestAny != null) {
            // `latest` promises the highest STABLE; silently serving a milestone/RC when a line
            // ships none (grails 8.x) breaks that promise. Pin the pre-release deliberately.
            throw new IllegalStateException("platform dependency `"
                    + group + ":" + artifact + "` selector `" + selector.raw()
                    + "` matches no stable version — newest is the pre-release " + bestAny
                    + "; pin it explicitly (e.g. `" + bestAny + "`) or use a snapshot selector");
        }
        throw new IllegalStateException("platform dependency `"
                + group
                + ":"
                + artifact
                + "` selector `"
                + selector.raw()
                + "` matches no version in configured repositories"
                + (available.isEmpty() ? " (no versions advertised)" : ""));
    }

    private static IllegalStateException reject(String group, String artifact, String raw) {
        return new IllegalStateException("platform dependency `"
                + group
                + ":"
                + artifact
                + "` must use an exact, caret/tilde, latest, or snapshot version (got `"
                + raw
                + "`). Open ranges are not supported for [platform-dependencies] BOMs — pin e.g."
                + " `=3.4.0`, `3.4.0`, or `latest`.");
    }
}
