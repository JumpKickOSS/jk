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
 * Resolve a platform BOM {@link VersionSelector} to a concrete release version (JK-1544 / JK-1545).
 *
 * <p>Platform BOMs may use caret/tilde anchors (or exact pins) but not {@code latest}. The managed
 * catalog is loaded from the <em>resolved</em> BOM POM — so {@code version = "4"} must pick the
 * highest stable 4.x before reading {@code dependencyManagement}.
 */
public final class PlatformBomVersions {

    private PlatformBomVersions() {}

    /**
     * Concrete version of {@code group:artifact} satisfying {@code selector}.
     *
     * <ul>
     *   <li>{@link VersionSelector.Exact} → that version (no metadata required).
     *   <li>{@link VersionSelector.Caret}/{@link VersionSelector.Tilde} → highest <em>stable</em>
     *       version in the selector's range that metadata advertises; falls back to the anchor if
     *       metadata is empty (offline / first-publish) when the anchor itself is in range.
     *   <li>Other selectors → rejected (same R6b rule as lock).
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

        String anchor = switch (selector) {
            case VersionSelector.Caret c -> c.version();
            case VersionSelector.Tilde t -> t.version();
            case VersionSelector.Exact ignored -> throw new IllegalStateException("unreachable");
            case VersionSelector.Range r -> throw reject(group, artifact, r.raw());
            case VersionSelector.Latest l -> throw reject(group, artifact, l.raw());
            case VersionSelector.Snapshot s -> throw reject(group, artifact, s.raw());
        };

        VersionSet allowed = VersionSelectors.toVersionSet(selector);
        Coordinate probe = Coordinate.of(group, artifact, anchor);
        List<String> available = repos.availableVersions(probe);

        String bestStable = null;
        String bestAny = null;
        for (String v : available) {
            if (!allowed.contains(v)) continue;
            if (bestAny == null || Versions.compare(v, bestAny) > 0) bestAny = v;
            if (!Versions.isStable(v)) continue;
            if (bestStable == null || Versions.compare(v, bestStable) > 0) bestStable = v;
        }
        if (bestStable != null) return bestStable;
        // Anchor is always preferred over an unstable-only catalog when it is in range.
        if (allowed.contains(anchor)) return anchor;
        if (bestAny != null) return bestAny;
        throw new IllegalStateException("platform dependency `"
                + group
                + ":"
                + artifact
                + "` selector `"
                + selector.raw()
                + "` matches no version in configured repositories"
                + (available.isEmpty() ? " (no versions advertised)" : ""));
    }

    /** Parse a user/plugin version string the same way {@code jk.toml} does (bare → caret). */
    public static String resolve(RepoGroup repos, String group, String artifact, String versionSpec)
            throws IOException, InterruptedException {
        return resolve(repos, group, artifact, VersionSelector.parseFloating(versionSpec));
    }

    private static IllegalStateException reject(String group, String artifact, String raw) {
        return new IllegalStateException("platform dependency `"
                + group
                + ":"
                + artifact
                + "` must use an exact or caret/tilde version (got `"
                + raw
                + "`). Floating selectors like `latest` or open ranges are not supported for"
                + " [platform-dependencies] BOMs — pin e.g. `=3.4.0` or `3.4.0`.");
    }
}
