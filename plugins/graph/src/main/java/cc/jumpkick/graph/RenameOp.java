// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Budget;
import cc.jumpkick.codegraph.CodeGraph;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code rename} operation for a method ({@code Type.method}). Plans the edits, writes them through the file-write path with an undo
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
        List<String> javaFiles = RenameProof.javaFiles(graph);
        try {
            plan = plan(w, graph, address, to, options);
            if (also != null && !also.isBlank()) RenameAlso.fold(root, plan, also);
        } catch (RenameRefusal refused) {
            return GraphService.Reply.of(w, String.valueOf(refused.getMessage()));
        }
        Set<String> modules = touched(graph, plan);
        List<String[]> refs = RenameProof.expected(root, graph.modules(), javaFiles, plan);
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

    /** The plan for renaming the method {@code address} to {@code to}. */
    static RenamePlan plan(
            GraphService.Workspace w, CodeGraph graph, String address, String to, Map<String, String> options)
            throws IOException {
        String kind = options.get("kind");
        if (kind != null && !kind.isBlank() && !kind.equals("method")) {
            throw new IllegalArgumentException("kind is method, not '" + kind + "'");
        }
        return GraphRename.rename(w, graph, address, to);
    }
}
