// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Budget;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.config.EnvValues;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code rename} operation, for every kind it renames: a method ({@code Type.method}), a field
 * ({@code Type.field}, with its accessors), a type ({@code Type}; a qualified {@code to} moves it),
 * a package ({@code a.b}). Plans the edits, writes them through the file-write path with an undo
 * record, keeps the candidates for {@code also}, and leaves the proof for the engine's check after
 * its compile ({@code phase=verify}, or {@code phase=undo} when the compile failed).
 */
final class RenameOp {

    private RenameOp() {}

    static GraphService.Reply run(GraphService.Workspace w, CodeGraph graph, GraphService.Request r)
            throws IOException {
        Path root = w.root();
        Path out = w.out();
        Map<String, String> options = r.options();
        String phase = options.get("phase");
        if ("verify".equals(phase)) return GraphService.Reply.of(w, RenameProof.verify(root, out, graph));
        if ("undo".equals(phase)) {
            return GraphService.Reply.of(
                    w, RenameProof.undo(root, out, options.getOrDefault("why", "the code does not compile")));
        }
        List<String> a = r.args();
        if (a.size() < 2 || a.get(0).isBlank() || a.get(1).isBlank()) {
            throw new IllegalArgumentException("needs what to rename and the new name");
        }
        String address = a.get(0).strip();
        String to = a.get(1).strip();
        String also = options.get("also");
        RenameStore.Pending pending = RenameStore.readPending(out);
        if (also != null
                && !also.isBlank()
                && pending != null
                && pending.address().equals(address)
                && pending.to().equals(to)) {
            Set<String> ids = RenameAlso.chosen(pending, also);
            RenamePlan plan = RenameAlso.plan(root, pending, ids);
            if (plan.after.isEmpty()) {
                return GraphService.Reply.of(w, String.join("\n", RenameAlso.answer(graph.modules(), plan, ids)));
            }
            Set<String> modules = touched(graph, plan);
            RenameStore.write(root, out, plan);
            RenameStore.writeProof(out, modules, Map.of(), List.of());
            return GraphService.Reply.of(
                    w, Budget.lines(RenameAlso.answer(graph.modules(), plan, ids), r.budget(), "Raise the budget"));
        }
        RenamePlan plan;
        SymbolRename.Ctx ctx = new SymbolRename.Ctx(w, graph);
        try {
            plan = plan(w, graph, ctx, address, to, options);
            if (also != null && !also.isBlank()) RenameAlso.fold(root, plan, also);
        } catch (RenameRefusal refused) {
            return GraphService.Reply.of(w, String.valueOf(refused.getMessage()));
        }
        Set<String> modules = touched(graph, plan);
        List<String[]> refs = RenameProof.expected(root, graph.modules(), ctx.javaFiles, plan);
        List<RenamePlan.Candidate> ordered = plan.ordered();
        if (!plan.after.isEmpty() || !plan.rawMoves.isEmpty()) RenameStore.write(root, out, plan);
        if (ordered.isEmpty() && plan.hazards.isEmpty()) RenameStore.clearPending(out);
        else RenameStore.writePending(out, address, to, ordered, plan.hazards);
        if (!plan.after.isEmpty()) RenameStore.writeProof(out, modules, plan.symbols, refs);
        // A method rename names the sets it could not see; every other kind lists their uses as candidates.
        List<String> unseen = plan.unseen;
        return new GraphService.Reply(
                Budget.lines(plan.render(graph.modules()), r.budget(), "Raise the budget for every line"), unseen);
    }

    /** The modules a plan writes in: the ones the check compiles. */
    static Set<String> touched(CodeGraph graph, RenamePlan plan) {
        Set<String> rels = new TreeSet<>(plan.after.keySet());
        rels.addAll(plan.before.keySet());
        rels.addAll(plan.rawMoves.values());
        return RenameStore.modules(graph.modules(), rels);
    }

    /** What {@code address} names and the plan for renaming it to {@code to}. */
    static RenamePlan plan(
            GraphService.Workspace w,
            CodeGraph graph,
            SymbolRename.Ctx c,
            String address,
            String to,
            Map<String, String> options)
            throws IOException {
        String kind = options.get("kind");
        if (kind == null || kind.isBlank()) kind = kindOf(c, graph, address);
        if (kind.equals("method")) return GraphRename.rename(w, graph, address, to);
        String stale = GraphRename.edited(w.indexes());
        if (stale != null) throw new RenameRefusal(stale);
        List<String> textFiles = RenameText.files(w.root());
        switch (kind) {
            case "field" -> {
                int dot = address.lastIndexOf('.');
                SymbolRename.Type owner = one(graph, address.substring(0, dot));
                String member = address.substring(dot + 1);
                if (!RenameNames.identifier(to)) throw new IllegalArgumentException("'" + to + "' is not a field name");
                if (member.equals(to)) throw new RenameRefusal("'" + address + "' is already named " + to + ".");
                return SymbolRename.field(
                        c,
                        owner,
                        member,
                        to,
                        EnvValues.parseBool(options.get("accessors")).orElse(true),
                        textFiles);
            }
            case "type" -> {
                SymbolRename.Type t = one(graph, address);
                if (!RenameNames.identifier(to)) throw new IllegalArgumentException("'" + to + "' is not a type name");
                if (t.simple().equals(to)) throw new RenameRefusal("'" + address + "' is already named " + to + ".");
                return SymbolRename.type(c, t, to, textFiles);
            }
            default -> throw new IllegalArgumentException("kind is method, field or type, not '" + kind + "'");
        }
    }

    /**
     * What an address names when no kind is given: {@code Type.m(…)} a method; a workspace type a
     * type; {@code Type.member} a field when the type declares that field and no method of the name
     * (a record component is a field: its accessor goes with it); a package a package; else a method,
     * whose answer says what there is.
     */
    static String kindOf(SymbolRename.Ctx c, CodeGraph graph, String address) throws IOException {
        if (address.contains("(")) return "method";
        List<SymbolRename.Type> types = SymbolRename.types(graph, address);
        if (types.size() == 1) return "type";
        if (types.size() > 1) return "type";
        int dot = address.lastIndexOf('.');
        if (dot > 0) {
            List<SymbolRename.Type> owners = SymbolRename.types(graph, address.substring(0, dot));
            String member = address.substring(dot + 1);
            if (owners.size() == 1) {
                GraphRename.Loaded l = c.classes().get(owners.getFirst().internal());
                if (l != null
                        && l.facts().fields().stream().anyMatch(f -> f.name().equals(member))) {
                    boolean record = "java/lang/Record".equals(l.facts().superName());
                    boolean method =
                            l.facts().methods().stream().anyMatch(m -> m.name().equals(member));
                    if (!method || record) return "field";
                }
                return "method";
            }
        }
        return "method";
    }

    private static SymbolRename.Type one(CodeGraph graph, String name) {
        List<SymbolRename.Type> types = SymbolRename.types(graph, name);
        if (types.isEmpty()) throw new RenameRefusal("No type '" + name + "' in the workspace.");
        if (types.size() > 1) {
            List<String> shown = new ArrayList<>();
            for (SymbolRename.Type t : types.subList(0, Math.min(types.size(), 8))) {
                shown.add(t.binary().replace('$', '.') + "  " + t.rel());
            }
            throw new RenameRefusal(
                    "'" + name + "' names " + types.size() + " types; qualify it:\n  " + String.join("\n  ", shown));
        }
        return types.getFirst();
    }
}
