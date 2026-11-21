// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.command.project.GraphOpCommand.BUDGET;
import static cc.jumpkick.command.project.GraphOpCommand.NO_REFRESH;
import static cc.jumpkick.command.project.GraphOpCommand.scoped;

import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.List;

/**
 * {@code jk graph}: the workspace's code graph itself — symbols, files, configuration and docs, and
 * how they connect, with calls and overrides from the compiled classes — built engine-side. Bare
 * {@code jk graph} builds it; {@code path} and {@code neighbors} walk it. Questions about the code and
 * edits to it are {@link CodeCommand jk code}. See {@code docs/user/graph.md}.
 */
public final class GraphCommand extends GroupCommand {

    @Override
    public String name() {
        return "graph";
    }

    @Override
    public String description() {
        return "Build and walk the code graph";
    }

    private static GraphOpCommand op(String name, String description, List<Opt> options, List<Param> parameters) {
        return new GraphOpCommand("graph", name, description, options, parameters);
    }

    private final GraphOpCommand build =
            op("build", "Build or refresh the code graph (-r re-reads every file)", List.of(), List.of());

    @Override
    public List<CliCommand> subcommands() {
        CodeCommand code = new CodeCommand();
        return List.of(
                build,
                op(
                        "path",
                        "Shortest path between two nodes",
                        List.of(
                                Opt.flag("Ignore edge direction.", "-U", "--undirected"),
                                Opt.value("<n>", "Longest path to report (default 8).", "-L", "--max-hops"),
                                NO_REFRESH),
                        List.of(
                                Param.of("from", Arity.ONE, "label or node id"),
                                Param.of("to", Arity.ONE, "label or node id"))),
                op(
                        "neighbors",
                        "A node's direct connections",
                        scoped(
                                Opt.value(
                                        "<relation>",
                                        "Only relations containing this (calls, ...).",
                                        "-R",
                                        "--relation"),
                                BUDGET,
                                NO_REFRESH),
                        List.of(Param.of("name", Arity.ONE, "label or node id"))),
                op(
                        "rules",
                        "The guard rules and NOTE/WHY comments that govern a file",
                        List.of(NO_REFRESH),
                        List.of(Param.of("file", Arity.ONE, "a path from the workspace root"))),
                moved("callers", "usages", code.usages),
                moved("rename", "rename", code.rename),
                moved("query", "query", code.query),
                moved("read", "read", code.read),
                moved("find", "find", code.find));
    }

    /** Released {@code jk graph <was>}, now {@code jk code <now>}. */
    private static MovedCommand moved(String was, String now, CliCommand target) {
        return new MovedCommand(was, "jk graph " + was, "jk code " + now, target);
    }

    @Override
    public CliCommand defaultSubcommand() {
        return build;
    }
}
