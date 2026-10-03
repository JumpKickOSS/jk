// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves a {@link NodeSpec} to an exact release with its per-platform digests. */
public final class NodeResolver {

    private final NodeCatalog catalog;

    public NodeResolver(NodeCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public NodeCatalog catalog() {
        return catalog;
    }

    /**
     * The release {@code spec} selects, with the digest of every {@link NodePlatform#LOCKED}
     * platform's archive and of {@code host}'s (a musl host's archive is not in the locked set).
     */
    public NodeResolution resolve(NodeSpec spec, NodePlatform host) throws IOException, InterruptedException {
        List<NodeRelease> releases = catalog.releases();
        NodeRelease release = NodeSelector.select(releases, spec);
        Map<String, String> byArchive = catalog.shasums(release.version());
        Map<String, String> sha256 = new LinkedHashMap<>();
        for (NodePlatform p : NodePlatform.LOCKED) put(sha256, byArchive, p, release.version());
        put(sha256, byArchive, host, release.version());
        if (!sha256.containsKey(host.key())) {
            throw new IOException("Node " + release.version() + " publishes no " + host.archiveType() + " archive for "
                    + host.key() + " (" + catalog.distBase() + ")");
        }
        return new NodeResolution(release.version(), release.npm(), release.lts(), sha256);
    }

    private static void put(Map<String, String> sha256, Map<String, String> byArchive, NodePlatform p, String version) {
        String hex = byArchive.get(p.archiveName(version));
        if (hex != null) sha256.put(p.key(), hex);
    }
}
