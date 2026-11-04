// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Version lookup for Giter8 {@code maven()} properties, against the repositories every other
 * resolve uses: the user's, over the public baseline, each routed through the mirror that stands
 * in for it.
 */
public final class Giter8Maven {

    private static final Pattern EXPR = Pattern.compile(
            "maven\\(\\s*([^,\\s]+)\\s*,\\s*([^,\\s]+)(?:\\s*,\\s*([^)]+))?\\s*\\)", Pattern.CASE_INSENSITIVE);

    private Giter8Maven() {}

    /**
     * The lookup over {@code repos}, asked for when a template carries a {@code maven()} property:
     * the newest release the repositories list, or the newest stable one when the property says
     * {@code stable}. {@link RepoGroup}'s version memo shares one catalog read per coordinate
     * across the template's properties.
     */
    public static MavenVersionLookup fromRepositories(Supplier<RepoGroup> repos, boolean offline) {
        return (groupId, artifact, stable) -> {
            if (offline) {
                throw new IOException("maven() properties require the network (offline)");
            }
            String key = groupId + ":" + artifact;
            List<String> versions;
            try {
                versions = repos.get().availableVersions(Coordinate.of(groupId, artifact, "0"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("maven() lookup interrupted", e);
            }
            String picked = pick(versions, stable);
            if (picked == null) {
                throw new IOException("maven() found no version for " + key);
            }
            return picked;
        };
    }

    /** The newest of {@code versions}, or the newest stable one; null when there is none. */
    static @Nullable String pick(List<String> versions, boolean stable) {
        String best = null;
        for (String v : versions) {
            if (v.isBlank() || Versions.isSnapshot(v) || (stable && unstable(v))) continue;
            if (best == null || Versions.compare(v, best) > 0) best = v;
        }
        return best;
    }

    static boolean isMavenExpr(@Nullable String value) {
        return value != null && EXPR.matcher(value.strip()).matches();
    }

    static String resolveExpr(String value, @Nullable MavenVersionLookup lookup) throws IOException {
        Matcher m = EXPR.matcher(value.strip());
        if (!m.matches()) return value;
        if (lookup == null) {
            throw new IOException("maven() property requires a version lookup: " + value.strip());
        }
        String third = m.group(3);
        boolean stable = third != null && third.strip().equalsIgnoreCase("stable");
        return lookup.latest(m.group(1).strip(), m.group(2).strip(), stable);
    }

    /** Not a release: the one definition of stable ({@link Versions#isStable}) — {@code 4.0.0.Beta1} included. */
    private static boolean unstable(String version) {
        return !Versions.isStable(version);
    }
}
