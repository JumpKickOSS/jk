// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.model.JkVersion;

/** The files a graph build writes under {@code target/graph/}. */
final class GraphOutputs {

    private GraphOutputs() {}

    /** graphify-compatible node-link graph: what other tools read. */
    static final String JSON = "graph.json";

    /** The same graph, compact, for this worker's own fast reload. */
    static final String SNAPSHOT = "graph.bin";

    /** The one-read orientation report. */
    static final String REPORT = "GRAPH_REPORT.md";

    static String generator() {
        return "jk " + JkVersion.VERSION;
    }
}
