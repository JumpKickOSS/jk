// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Command-line programs in compiled classes, picocli's: a class's {@code @Command} names a command,
 * its {@code subcommands} the commands under it, and each {@code @Option} on a field or method of it
 * or its superclasses a flag. Each command defines {@code cli:<program> <subcommand…>} and each flag
 * {@code cli:<program> <subcommand…> <flag>}, declared, from the command's class, the flag's member
 * named on the edge. A program is a command no other command lists, named by its own {@code name};
 * one with no name gives no anchor. Mixins and method subcommands are not read.
 */
final class JvmCli {

    private JvmCli() {}

    private static final String COMMAND = "Lpicocli/CommandLine$Command;";
    private static final String OPTION = "Lpicocli/CommandLine$Option;";
    private static final List<String> STANDARD_HELP = List.of("-h", "-" + "-help", "-V", "-" + "-version");

    /** Deeper than any real command tree: a subcommand cycle stops here. */
    private static final int MAX_DEPTH = 8;

    /** Where a compiled class sits in the graph: its node and source file. */
    record Placed(String id, String rel) {}

    /** The command anchors of the classes in {@code index} that {@code placed} holds. */
    static void commands(
            FactsIndex index, Map<String, Placed> placed, Map<String, GraphNode> nodes, List<GraphEdge> edges) {
        Map<String, ClassFacts> commands = new LinkedHashMap<>();
        Set<String> listed = new HashSet<>();
        for (ClassFacts c : index.classList()) {
            AnnotationFacts a = command(c.annotations());
            if (a == null) continue;
            commands.put(c.name(), c);
            listed.addAll(a.values().getOrDefault("subcommands", List.of()));
        }
        for (ClassFacts c : commands.values()) {
            if (listed.contains(c.name())) continue;
            String name = name(command(c.annotations()));
            if (name != null) walk(index, commands, placed, c, name, 0, nodes, edges);
        }
    }

    private static void walk(
            FactsIndex index,
            Map<String, ClassFacts> commands,
            Map<String, Placed> placed,
            ClassFacts c,
            String path,
            int depth,
            Map<String, GraphNode> nodes,
            List<GraphEdge> edges) {
        AnnotationFacts a = command(c.annotations());
        Placed at = placed.get(c.name());
        if (a == null || at == null || depth > MAX_DEPTH) return;
        define(at, path, null, nodes, edges);
        if (a.values().getOrDefault("mixinStandardHelpOptions", List.of()).contains("true")) {
            for (String flag : STANDARD_HELP) define(at, path + " " + flag, null, nodes, edges);
        }
        ClassFacts k = c;
        for (int up = 0; k != null && up <= MAX_DEPTH; up++) {
            for (FieldFacts f : k.fields()) options(f.annotations(), at, path, "field " + f.name(), nodes, edges);
            for (MethodFacts m : k.methods()) options(m.annotations(), at, path, "method " + m.name(), nodes, edges);
            String sup = k.superName();
            k = sup == null ? null : index.classNamed(sup).orElse(null);
        }
        for (String sub : a.values().getOrDefault("subcommands", List.of())) {
            ClassFacts s = commands.get(sub);
            String name = s == null ? null : name(command(s.annotations()));
            if (s != null && name != null) walk(index, commands, placed, s, path + " " + name, depth + 1, nodes, edges);
        }
    }

    private static void options(
            List<AnnotationFacts> annotations,
            Placed at,
            String path,
            String member,
            Map<String, GraphNode> nodes,
            List<GraphEdge> edges) {
        for (AnnotationFacts a : annotations) {
            if (!a.desc().equals(OPTION)) continue;
            for (String flag : a.values().getOrDefault("names", List.of())) {
                if (!flag.isBlank()) define(at, path + " " + flag.strip(), member, nodes, edges);
            }
        }
    }

    private static void define(
            Placed at, String name, @Nullable String member, Map<String, GraphNode> nodes, List<GraphEdge> edges) {
        GraphNode n = Anchors.node(Anchors.CLI, name);
        nodes.merge(n.id(), n, Anchors::preferred);
        GraphEdge e = Anchors.edge(at.id(), Anchors.CLI, name, Relations.DEFINES, Confidence.DECLARED, at.rel(), 0);
        edges.add(member == null ? e : e.withContext(member));
    }

    private static @Nullable AnnotationFacts command(List<AnnotationFacts> annotations) {
        for (AnnotationFacts a : annotations) {
            if (a.desc().equals(COMMAND)) return a;
        }
        return null;
    }

    private static @Nullable String name(@Nullable AnnotationFacts command) {
        if (command == null) return null;
        List<String> names = command.values().getOrDefault("name", List.of());
        String name = names.isEmpty() ? "" : names.getFirst().strip();
        return name.isEmpty() || name.contains(" ") ? null : name;
    }
}
