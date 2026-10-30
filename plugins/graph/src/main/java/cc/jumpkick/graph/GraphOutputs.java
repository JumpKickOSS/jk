// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

/** The files a graph build writes under {@code target/graph/}. */
final class GraphOutputs {

    private GraphOutputs() {}

    /** The graph in {@link cc.jumpkick.codegraph.GraphSnapshot}'s compact form: what every read loads. */
    static final String SNAPSHOT = "graph.bin";
}
