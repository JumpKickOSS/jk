// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The number a writer pins when the user named a coordinate without a version: the newest stable
 * release the repositories advertise. {@code jk add}, {@code jk new} and MCP {@code jk_deps} write
 * this number into {@code jk.toml}; nothing writes {@code latest}.
 */
public final class StableVersions {

    private StableVersions() {}

    /**
     * The selector a manifest edit writes: {@code latest} becomes the newest stable release of
     * {@code group:artifact} in the repositories {@code manifest} declares; any other selector is
     * written as given.
     */
    public static String pinnedVersion(Path manifest, String group, String artifact, String selector)
            throws IOException {
        if (!(VersionSelector.parse(selector) instanceof VersionSelector.Latest)) return selector;
        var project = JkBuildParser.parse(manifest);
        var repos = RepoGroupBuilder.buildFor(project, null, JkStores.storeCas());
        return newest(repos, group, artifact);
    }

    /**
     * Newest stable release of {@code group:artifact} in {@code repos}.
     *
     * @throws IOException when no repository could be reached, none advertises the coordinate, or
     *     every advertised release is a pre-release; the message tells the user to pass an explicit
     *     version
     */
    public static String newest(RepoGroup repos, String group, String artifact) throws IOException {
        String coord = group + ":" + artifact;
        List<String> available;
        try {
            available = repos.availableVersions(Coordinate.of(group, artifact, "0"));
        } catch (IOException e) {
            throw new IOException(
                    "could not look up the current version of " + coord + " (" + e.getMessage()
                            + "); pass an explicit version, e.g. " + coord + ":1.2.3",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while looking up the current version of " + coord, e);
        }
        String stable = null;
        String any = null;
        for (String v : available) {
            if (any == null || Versions.compare(v, any) > 0) any = v;
            if (!Versions.isStable(v)) continue;
            if (stable == null || Versions.compare(v, stable) > 0) stable = v;
        }
        if (stable != null) return stable;
        if (any != null) {
            throw new IOException(coord + " has no stable release; the newest is the pre-release " + any
                    + " — pass it explicitly, e.g. " + coord + ":" + any);
        }
        throw new IOException("no versions of " + coord
                + " in the configured repositories; check the coordinate or pass an explicit version, e.g. " + coord
                + ":1.2.3");
    }
}
