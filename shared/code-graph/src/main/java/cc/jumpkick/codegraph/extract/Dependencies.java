// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.Relations;

/**
 * Library dependencies as shared external nodes: every build file that names {@code group:artifact}
 * (jk.toml, pom.xml, Gradle, a version catalog) points at the same node, and so does every
 * package.json naming an npm package.
 */
final class Dependencies {

    private Dependencies() {}

    static void declare(FragmentBuilder b, String from, String group, String artifact, int line) {
        String id = b.external(GraphIds.make("maven", group, artifact), group + ":" + artifact);
        b.edge(from, id, Relations.DEPENDS_ON, Confidence.EXTRACTED, line);
    }

    static void npm(FragmentBuilder b, String from, String name, int line) {
        String id = b.external(GraphIds.make("npm", name), name);
        b.edge(from, id, Relations.DEPENDS_ON, Confidence.EXTRACTED, line);
    }
}
