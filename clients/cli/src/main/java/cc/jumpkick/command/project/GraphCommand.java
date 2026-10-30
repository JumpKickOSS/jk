// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk graph}: the workspace's code graph — symbols, files, configuration and docs, and how
 * they connect, with calls and overrides from the compiled classes — built engine-side and queried
 * within a token budget. Bare {@code jk graph} builds it; every read refreshes it first. Answers
 * name a symbol {@code Owner.member()} at {@code file:line}. See {@code docs/user/graph.md}.
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

    /** The options that narrow a read to part of the workspace. */
    static final List<Opt> SCOPE = List.of(
            Opt.value("<globs>", "Only these paths; ! excludes.", "-g", "--scope"),
            CommonOpts.modules("Only these modules (globs; repeatable)."),
            Opt.value("<main|test>", "Only main or test sources.", "-S", "--source-set"));

    private static List<Opt> scoped(Opt... options) {
        List<Opt> out = new ArrayList<>(List.of(options));
        out.addAll(SCOPE);
        return List.copyOf(out);
    }

    private final GraphOpCommand build = new GraphOpCommand(
            "build", "Build or refresh target/graph/ (-r re-reads every file)", List.of(), List.of());

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                build,
                new GraphOpCommand(
                        "query",
                        "Answer a question with the relevant subgraph",
                        scoped(
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
                        "path",
                        "Shortest path between two nodes",
                        List.of(
                                Opt.flag("Ignore edge direction.", "-U", "--undirected"),
                                Opt.value("<n>", "Longest path to report (default 8).", "-L", "--max-hops"),
                                NO_REFRESH),
                        List.of(
                                Param.of("from", Arity.ONE, "label or node id"),
                                Param.of("to", Arity.ONE, "label or node id"))),
                new GraphOpCommand(
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
                new GraphOpCommand(
                        "callers",
                        "Who calls a method, complete, with each call's file:line",
                        scoped(
                                Opt.flag("Also their callers, and theirs: all that reach it.", "-t", "--transitive"),
                                Opt.value("<n>", "Most hops with -t (default 8).", "-L", "--max-hops"),
                                BUDGET,
                                NO_REFRESH),
                        List.of(Param.of("method", Arity.ONE, "Type.method (every overload)"))),
                new GraphOpCommand(
                        "read",
                        "A symbol's source or a type's members, in the view that fits",
                        scoped(
                                Opt.value(
                                        "<view>",
                                        "content, structure or headline (default: what fits).",
                                        "-w",
                                        "--view"),
                                BUDGET,
                                NO_REFRESH),
                        List.of(Param.of(
                                "address", Arity.ONE_OR_MORE, "Type, Type.member, Type.*, a path or glob; #line=N,M"))),
                new GraphOpCommand(
                        "find",
                        "Declarations by kind, visibility, supertype, annotation, name",
                        scoped(
                                Opt.value("<kinds>", "class, interface, method, ... (type, member).", "-t", "--kind"),
                                Opt.value(
                                        "<vis>",
                                        "public, protected, internal, package, private.",
                                        "-p",
                                        "--visibility"),
                                Opt.value("<type>", "A superclass anywhere up the chain.", "-E", "--extends"),
                                Opt.value(
                                        "<type>", "A supertype anywhere above, interfaces too.", "-I", "--implements"),
                                Opt.value("<annotation>", "Carries this annotation.", "-A", "--annotated"),
                                Opt.flag("Search the locked jars' types instead.", "-l", "--libraries"),
                                BUDGET,
                                NO_REFRESH),
                        List.of(Param.of("name", Arity.ZERO_OR_ONE, "a name glob: *Service, get*"))),
                new GraphOpCommand(
                        "rules",
                        "The guard rules and NOTE/WHY comments that govern a file",
                        List.of(NO_REFRESH),
                        List.of(Param.of("file", Arity.ONE, "a path from the workspace root"))));
    }

    @Override
    public CliCommand defaultSubcommand() {
        return build;
    }
}
