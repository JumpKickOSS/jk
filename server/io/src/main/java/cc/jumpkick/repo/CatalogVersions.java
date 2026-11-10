// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.model.Coordinate;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The versions a repository's catalog lists, refusing a body that is not a catalog. */
final class CatalogVersions {

    private CatalogVersions() {}

    /**
     * The versions {@code xml}, {@code repo}'s answer at {@code uri}, lists for {@code coord}. A body
     * that is not maven-metadata.xml (an SSO login page, a proxy error served with 200) is that
     * repository's answer alone: it is dropped from {@code cache}, so the next resolve asks again,
     * and raised as an {@link IOException}, so the group still asks the others.
     */
    static List<String> of(byte[] xml, Coordinate coord, String repo, URI uri, @Nullable MavenMetadataCache cache)
            throws IOException {
        try {
            return MavenMetadata.parse(xml, coord.group(), coord.artifact()).versions();
        } catch (IllegalArgumentException notMetadata) {
            if (cache != null) cache.forget(uri);
            throw new IOException(
                    repo + " answered " + uri + " with a document that is not maven-metadata.xml", notMetadata);
        }
    }
}
