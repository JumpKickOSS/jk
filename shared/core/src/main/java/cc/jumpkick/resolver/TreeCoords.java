// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.PackageId;
import cc.jumpkick.resolver.DependencyTreeStyle.Styling;

/**
 * How a module key becomes a printable coordinate. One owner, because the tree renders the same
 * coordinate three ways — a nested node, a flattened row, and a collapsed sibling reference — and
 * the split from a lock/solver key ({@code g:a:type:classifier}) down to the displayed {@code g:a}
 * was written out longhand at each of them. Three copies of a fallback is three chances for one of
 * them to disagree about a malformed key.
 */
final class TreeCoords {

    private TreeCoords() {}

    /** The displayed halves of a module key. */
    record Ga(String group, String artifact) {}

    /**
     * A module key as {@code group}/{@code artifact} for display. A Maven package key (2–4
     * segments) is parsed so {@code g:a:jar:} shows as {@code g:a}; anything else — a
     * {@code workspace:}/{@code git:}/{@code path:} synthetic, or a key whose shape passed the
     * check but failed the parse — falls back to {@link #onFirstColon}.
     */
    static Ga split(String module) {
        if (PackageId.isMavenPackageKey(module)) {
            try {
                PackageId id = PackageId.parse(module);
                return new Ga(id.group(), id.artifact());
            } catch (RuntimeException ignored) {
                // shape passed, parse did not — show the raw halves rather than nothing
            }
        }
        return onFirstColon(module);
    }

    /** Raw halves: everything before the first colon, everything after. */
    static Ga onFirstColon(String module) {
        int colon = module.indexOf(':');
        return new Ga(colon > 0 ? module.substring(0, colon) : module, colon > 0 ? module.substring(colon + 1) : "");
    }

    /** {@code group:artifact:version}, each segment through its styler. */
    static String formatCoord(String group, String artifact, String version, Styling styling) {
        return styling.group().apply(group)
                + ":"
                + styling.artifact().apply(artifact)
                + ":"
                + styling.version().apply(version);
    }

    /** {@code group:artifact} styled, no version — a workspace sibling reference carries none. */
    static String coordLabel(String module, Styling styling) {
        Ga ga = onFirstColon(module);
        return styling.group().apply(ga.group()) + ":" + styling.artifact().apply(ga.artifact());
    }

    /** {@code group:artifact:version} when a version is known, else {@code group:artifact}. */
    static String coordVersioned(String module, String version, Styling styling) {
        Ga ga = split(module);
        return version == null
                ? styling.group().apply(ga.group()) + ":" + styling.artifact().apply(ga.artifact())
                : formatCoord(ga.group(), ga.artifact(), version, styling);
    }
}
