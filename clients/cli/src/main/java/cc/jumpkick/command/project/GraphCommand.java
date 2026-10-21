// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.List;

/**
 * {@code jk graph}: the workspace's code graph — symbols, files, configuration and docs, and how
 * they connect — built engine-side and queried within a token budget. Bare {@code jk graph} builds
 * it; every read refreshes it first. Format and tools follow graphify's, so an agent's graphify
 * habits work unchanged. See {@code docs/user/graph.md}.
 */
public final class GraphCommand extends GroupCommand {

    @Override
    public String name() {
        return "graph";
    }

    @Override
    public String description() {
        return "Build and query the code graph";
    }

    private static final Opt NO_REFRESH =
            Opt.flag("Answer from the last graph; skip the refresh.", "-n", "--no-refresh");
    private static final Opt BUDGET = Opt.value("<tokens>", "Answer size in tokens (default 2000).", "-b", "--budget");

    private final GraphOpCommand build = new GraphOpCommand(
            "build", "Build or refresh target/graph/ (-r re-reads every file)", List.of(), List.of());

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                build,
                new GraphOpCommand(
                        "query",
                        "Answer a question with the relevant subgraph",
                        List.of(
                                Opt.flag("Walk depth-first.", "-s", "--dfs"),
                                Opt.value("<n>", "Hops from the matched nodes (default 3).", "-d", "--depth"),
                                BUDGET,
                                Opt.value(
                                                "<a,b,...>",
                                                "Follow only these edge contexts (call, field, ...).",
                                                "-c",
                                                "--context")
                                        .splitOn(","),
                                NO_REFRESH),
                        List.of(Param.of("question", Arity.ONE_OR_MORE, "words or symbol names"))),
                new GraphOpCommand(
                        "explain",
                        "Show one node and its connections",
                        List.of(Opt.flag("List every connection, not the top 20.", "-A", "--all"), NO_REFRESH),
                        List.of(Param.of("name", Arity.ONE, "label or node id"))),
                new GraphOpCommand(
                        "path",
                        "Shortest path between two nodes",
                        List.of(
                                Opt.flag("Ignore edge direction.", "-U", "--undirected"),
                                Opt.value("<n>", "Longest path to report (default 8).", "-m", "--max-hops"),
                                NO_REFRESH),
                        List.of(
                                Param.of("from", Arity.ONE, "label or node id"),
                                Param.of("to", Arity.ONE, "label or node id"))),
                new GraphOpCommand(
                        "node",
                        "One node's details",
                        List.of(NO_REFRESH),
                        List.of(Param.of("name", Arity.ONE, "label or node id"))),
                new GraphOpCommand(
                        "neighbors",
                        "A node's direct connections",
                        List.of(
                                Opt.value(
                                        "<relation>",
                                        "Only relations containing this (calls, ...).",
                                        "-R",
                                        "--relation"),
                                BUDGET,
                                NO_REFRESH),
                        List.of(Param.of("name", Arity.ONE, "label or node id"))),
                new GraphOpCommand(
                        "community",
                        "The members of one community",
                        List.of(BUDGET, NO_REFRESH),
                        List.of(Param.of("id", Arity.ONE, "community number"))),
                new GraphOpCommand(
                        "god-nodes",
                        "The most-connected entities",
                        List.of(Opt.value("<n>", "How many (default 10).", "-t", "--top"), NO_REFRESH),
                        List.of()),
                new GraphOpCommand("stats", "Node, edge and community counts", List.of(NO_REFRESH), List.of()),
                new GraphOpCommand("report", "Print GRAPH_REPORT.md", List.of(NO_REFRESH), List.of()));
    }

    @Override
    public CliCommand defaultSubcommand() {
        return build;
    }
}
