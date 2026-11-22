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
 * {@code jk code}: questions about the code and edits to it, answered from the code graph: who uses
 * a symbol, its source, declarations by shape, what governs a file, and renames, deletes, moves and
 * extracted interfaces
 * that edit every use and prove the result compiles. Each refreshes the graph first. See {@code docs/user/graph.md}.
 */
public final class CodeCommand extends GroupCommand {

    @Override
    public String name() {
        return "code";
    }

    @Override
    public String description() {
        return "Ask about the code and refactor it";
    }

    private static final Param MEMBER = Param.of("member", Arity.ONE, "Type.method (every overload) or Type.field");

    private static GraphOpCommand op(String name, String description, List<Opt> options, List<Param> parameters) {
        return new GraphOpCommand("code", name, description, options, parameters);
    }

    final GraphOpCommand usages = op(
            "usages",
            "Each use of a method, field, type or anchor, at file:line",
            scoped(
                    Opt.flag("Also their callers, and theirs: all that reach it.", "-t", "--transitive"),
                    Opt.value("<n>", "Most hops with -t (default 8).", "-L", "--max-hops"),
                    BUDGET,
                    NO_REFRESH),
            List.of(Param.of("symbol", Arity.ONE, "Type.method, Type.field, Type, @Annotation or an anchor")));

    final GraphOpCommand rename = op(
            "rename",
            "Rename a method, field, type or package, or move a type",
            List.of(
                    Opt.value(
                            "<ids>",
                            "Apply candidates the last rename listed: c1,c3,h1, declared, all.",
                            "-A",
                            "--also"),
                    Opt.value("<kind>", "method, field, type or package (default: what it names).", "-t", "--kind"),
                    Opt.flag("A field alone, not its getX/setX.", "-N", "--no-accessors"),
                    Opt.value("<dir>", "The module a moved type lands in.", "-m", "--module"),
                    BUDGET),
            List.of(
                    Param.of("symbol", Arity.ONE, "Type.method[(int,String)], Type.field, Type or a.package"),
                    Param.of("name", Arity.ONE, "the new name; a qualified name moves a type")));

    final GraphOpCommand query = op(
            "query",
            "Answer a question with the relevant subgraph",
            scoped(
                    Opt.flag("Walk depth-first.", "-s", "--dfs"),
                    Opt.value("<n>", "Hops from the matched nodes (default 3).", "-d", "--depth"),
                    BUDGET,
                    Opt.value("<a,b,...>", "Follow only these edge contexts (call, field, ...).", "-c", "--context")
                            .splitOn(","),
                    Opt.value("<glob>", "Ask a locked library's public API instead; * for all.", "-l", "--library"),
                    NO_REFRESH),
            List.of(Param.of("question", Arity.ONE_OR_MORE, "words or symbol names")));

    final GraphOpCommand read = op(
            "read",
            "A symbol's source or a type's members, sized to fit",
            scoped(
                    Opt.value("<view>", "content, structure or headline (default: what fits).", "-w", "--view"),
                    BUDGET,
                    NO_REFRESH),
            List.of(Param.of("address", Arity.ONE_OR_MORE, "Type, Type.member, Type.*, a path or glob; #line=N,M")));

    final GraphOpCommand find = op(
            "find",
            "Declarations by kind, supertype, annotation and name glob",
            scoped(
                    Opt.value("<kinds>", "class, interface, method, ... (type, member).", "-t", "--kind"),
                    Opt.value("<vis>", "public, protected, internal, package, private.", "-p", "--visibility"),
                    Opt.value("<type>", "A superclass anywhere up the chain.", "-E", "--extends"),
                    Opt.value("<type>", "A supertype anywhere above, interfaces too.", "-I", "--implements"),
                    Opt.value("<annotation>", "Carries this annotation.", "-A", "--annotated"),
                    Opt.value("<glob>", "Search a locked library's types instead; * for all.", "-l", "--library"),
                    BUDGET,
                    NO_REFRESH),
            List.of(Param.of("name", Arity.ZERO_OR_ONE, "a name glob: *Service, get*")));

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                usages,
                find,
                read,
                query,
                rename,
                op(
                        "delete",
                        "Delete what nothing uses; else list what uses it",
                        List.of(
                                Opt.value("<kind>", "method, field or type (default: what it names).", "-t", "--kind"),
                                BUDGET),
                        List.of(Param.of("symbol", Arity.ONE, "Type.method (every overload), Type.field or Type"))),
                op(
                        "move",
                        "Move a method or static field, editing every use",
                        List.of(
                                Opt.value(
                                        "<route>",
                                        "The route an instance method takes, as the last move listed: f1, p1.",
                                        "--via"),
                                BUDGET),
                        List.of(MEMBER, Param.of("type", Arity.ONE, "the class, enum or record it moves to"))),
                op(
                        "pull-up",
                        "Move an instance member up to the parent class",
                        List.of(BUDGET),
                        List.of(MEMBER, Param.of("type", Arity.ZERO_OR_ONE, "the class its type extends (default)"))),
                op(
                        "extract-interface",
                        "Create an interface a class implements, from its methods",
                        List.of(
                                Opt.value(
                                        "<a,b,c>",
                                        "Methods, every overload (default: public, overriding none).",
                                        "--methods"),
                                Opt.flag("Only list the places that could take it; switch none.", "--no-switch"),
                                BUDGET),
                        List.of(
                                Param.of("class", Arity.ONE, "the class, enum or record"),
                                Param.of("interface", Arity.ONE, "its name; qualified, it lands in that package"))),
                op(
                        "rules",
                        "The guard rules and NOTE/WHY comments that govern a file",
                        List.of(NO_REFRESH),
                        List.of(Param.of("file", Arity.ONE, "a path from the workspace root"))));
    }
}
