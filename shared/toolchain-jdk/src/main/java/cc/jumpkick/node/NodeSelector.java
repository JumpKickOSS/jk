// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import java.util.List;
import java.util.Optional;

/**
 * Picks the release a {@link NodeSpec} means from the catalog, and judges whether an install
 * already on disk satisfies one. Pre-releases are never picked.
 */
public final class NodeSelector {

    private NodeSelector() {}

    /**
     * The release {@code spec} resolves to: a major or a line to its newest, {@code lts} to the
     * newest LTS, {@code lts/<codename>} to that codename's newest, {@code latest} to the newest,
     * a point release to itself. {@code releases} is newest first, as the index lists them.
     */
    public static NodeRelease select(List<NodeRelease> releases, NodeSpec spec) {
        for (NodeRelease r : releases) {
            if (!r.preRelease() && matches(spec, r)) return r;
        }
        throw new IllegalArgumentException("no Node release matches " + spec + nearest(releases, spec));
    }

    /**
     * Whether an install of {@code version} satisfies {@code spec}: a required point release only
     * exactly; any other numeric form at the same major; {@code lts} / {@code lts/<codename>} when
     * the catalog lists the version as such; {@code latest} when it is the newest release's major.
     */
    public static boolean satisfies(NodeSpec spec, String version, List<NodeRelease> releases) {
        String v = version.startsWith("v") ? version.substring(1) : version;
        return switch (spec.kind()) {
            case EXACT -> spec.required() ? spec.text().equals(v) : NodeRelease.majorOf(v) == spec.major();
            case MAJOR, LINE -> NodeRelease.majorOf(v) == spec.major();
            case LTS, LTS_CODENAME ->
                find(releases, v).map(r -> matches(spec, r)).orElse(false);
            case LATEST ->
                releases.stream()
                        .filter(r -> !r.preRelease())
                        .findFirst()
                        .map(newest -> newest.major() == NodeRelease.majorOf(v))
                        .orElse(false);
        };
    }

    private static boolean matches(NodeSpec spec, NodeRelease r) {
        return switch (spec.kind()) {
            case MAJOR -> r.major() == spec.major();
            case LINE -> r.version().startsWith(spec.text() + ".");
            case EXACT -> r.version().equals(spec.text());
            case LTS -> r.lts() != null;
            case LTS_CODENAME -> {
                String codename = r.lts();
                yield codename != null && codename.equalsIgnoreCase(spec.text());
            }
            case LATEST -> true;
        };
    }

    private static Optional<NodeRelease> find(List<NodeRelease> releases, String version) {
        return releases.stream().filter(r -> r.version().equals(version)).findFirst();
    }

    private static String nearest(List<NodeRelease> releases, NodeSpec spec) {
        if (spec.major() == 0) return "";
        return releases.stream()
                .filter(r -> !r.preRelease() && r.major() == spec.major())
                .findFirst()
                .map(r -> " (newest " + spec.major() + ".x is " + r.version() + ")")
                .orElse(" (Node publishes no " + spec.major() + ".x release)");
    }
}
