// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A {@code Type.member} address names the member, not the type, and every overload answers. */
class GraphToolsTest {

    private static CodeGraph shop() {
        GraphNode orders = new GraphNode("orders", "OrderService", "code", "src/shop/OrderService.java", "L3");
        GraphNode total = new GraphNode("orders_total", ".total()", "code", "src/shop/OrderService.java", "L10");
        GraphNode totalTax = new GraphNode("orders_total_2", ".total()", "code", "src/shop/OrderService.java", "L20");
        GraphNode cart = new GraphNode("cart", "Cart", "code", "src/cart/Cart.java", "L3");
        GraphNode cartTotal = new GraphNode("cart_total", ".total()", "code", "src/cart/Cart.java", "L8");
        GraphNode checkout = new GraphNode("checkout", ".checkout()", "code", "src/shop/Checkout.java", "L5");
        GraphNode invoice = new GraphNode("invoice", ".invoice()", "code", "src/shop/Invoice.java", "L7");
        List<GraphEdge> edges = List.of(
                GraphEdge.of(
                        "orders",
                        "orders_total",
                        Relations.METHOD,
                        Confidence.EXTRACTED,
                        "src/shop/OrderService.java",
                        10),
                GraphEdge.of(
                        "orders",
                        "orders_total_2",
                        Relations.METHOD,
                        Confidence.EXTRACTED,
                        "src/shop/OrderService.java",
                        20),
                GraphEdge.of("cart", "cart_total", Relations.METHOD, Confidence.EXTRACTED, "src/cart/Cart.java", 8),
                GraphEdge.of(
                        "checkout", "orders_total", Relations.CALLS, Confidence.EXTRACTED, "src/shop/Checkout.java", 6),
                GraphEdge.of(
                        "invoice", "orders_total_2", Relations.CALLS, Confidence.EXTRACTED, "src/shop/Invoice.java", 9),
                GraphEdge.of(
                        "checkout", "cart_total", Relations.CALLS, Confidence.EXTRACTED, "src/shop/Checkout.java", 7));
        return CodeGraph.merge(
                List.of(new Fragment(List.of(orders, total, totalTax, cart, cartTotal, checkout, invoice), edges)));
    }

    @Test
    void a_member_address_resolves_to_the_member_of_that_type() {
        GraphTools tools = new GraphTools(shop());
        assertThat(tools.resolve("Cart.total").id()).isEqualTo("cart_total");
        assertThat(tools.resolve("cart.Cart.total()").id()).isEqualTo("cart_total");
        assertThat(tools.resolve("OrderService.total").error())
                .startsWith("'OrderService.total' names 2 members; pass one id:");
        assertThat(tools.resolve("Cart.price").error())
                .as("a missing member is said so, not answered with the type's connections")
                .isEqualTo("'Cart' (src/cart/Cart.java) declares no member 'price'; its members: total");
    }

    @Test
    void one_edge_keeps_every_call_site_it_was_found_at() {
        GraphNode a = new GraphNode("split", ".split()", "code", "src/S.java", "L5");
        GraphNode b = new GraphNode("worker", ".worker()", "code", "src/S.java", "L40");
        CodeGraph g = CodeGraph.merge(List.of(new Fragment(
                List.of(a, b),
                List.of(
                        GraphEdge.of("split", "worker", Relations.CALLS, Confidence.EXTRACTED, "src/S.java", 21),
                        GraphEdge.of("split", "worker", Relations.CALLS, Confidence.EXTRACTED, "src/S.java", 9),
                        GraphEdge.of("split", "worker", Relations.CALLS, Confidence.EXTRACTED, "src/S.java", 21)))));
        assertThat(g.edges()).hasSize(1);
        assertThat(new GraphTools(g).neighbors("worker", "calls", 2000))
                .contains("  <-calls- .split()  src/S.java:21,9");

        StringBuilder source = new StringBuilder();
        for (int i = 1; i <= 30; i++) source.append(i == 9 || i == 21 ? "    worker(" + i + ");\n" : "\n");
        String quoted = new GraphTools(g, Scope.ALL, rel -> rel.equals("src/S.java") ? source.toString() : null)
                .neighbors("worker", "calls", 2000);
        assertThat(quoted)
                .as("each site's own line, so the call is edited without reading the file")
                .contains("  src/S.java:21,9\n      L21| worker(21);\n      L9| worker(9);");
        assertThat(new GraphTools(g, Scope.ALL, rel -> null).neighbors("worker", "calls", 2000))
                .doesNotContain("L21|");
    }

    @Test
    void neighbors_of_an_overloaded_member_lists_every_overload() {
        String out = new GraphTools(shop()).neighbors("OrderService.total", "calls", 2000);
        assertThat(out)
                .contains("Neighbors of OrderService.total()  src/shop/OrderService.java:10:")
                .contains("Neighbors of OrderService.total()  src/shop/OrderService.java:20:")
                .contains("<-calls- .checkout()  src/shop/Checkout.java:6")
                .contains("<-calls- .invoice()  src/shop/Invoice.java:9")
                .doesNotContain("Cart");
        assertThat(new GraphTools(shop()).neighbors("Cart.total", "calls", 2000))
                .startsWith("Neighbors of Cart.total():")
                .contains("<-calls- .checkout()  src/shop/Checkout.java:7")
                .doesNotContain("invoice");
    }

    /**
     * {@code Release.publish} calls {@code Info.name} (twice) and {@code Release.Plan.label}, a nested
     * record's member, calls it too; {@code Cli.run} calls {@code publish}; a test calls {@code name}.
     */
    private static CodeGraph release() {
        String rel = "src/main/java/a/Release.java";
        String info = "src/main/java/a/Info.java";
        String cli = "src/main/java/a/Cli.java";
        String test = "src/test/java/a/InfoTest.java";
        List<GraphNode> nodes = List.of(
                type("info", "Info", "a.Info", info),
                new GraphNode("info_name", ".name()", "code", info, "L4"),
                type("release", "Release", "a.Release", rel),
                new GraphNode("release_publish", ".publish()", "code", rel, "L8"),
                type("plan", "Plan", "a.Release$Plan", rel),
                new GraphNode("plan_label", ".label()", "code", rel, "L20"),
                type("cli", "Cli", "a.Cli", cli),
                new GraphNode("cli_run", ".run()", "code", cli, "L5"),
                type("infotest", "InfoTest", "a.InfoTest", test),
                new GraphNode("infotest_names", ".names()", "code", test, "L9"));
        List<GraphEdge> edges = List.of(
                GraphEdge.of("info", "info_name", Relations.METHOD, Confidence.EXTRACTED, info, 4),
                GraphEdge.of("release", "release_publish", Relations.METHOD, Confidence.EXTRACTED, rel, 8),
                GraphEdge.of("release", "plan", Relations.CONTAINS, Confidence.EXTRACTED, rel, 19),
                GraphEdge.of("plan", "plan_label", Relations.METHOD, Confidence.EXTRACTED, rel, 20),
                GraphEdge.of("cli", "cli_run", Relations.METHOD, Confidence.EXTRACTED, cli, 5),
                GraphEdge.of("infotest", "infotest_names", Relations.METHOD, Confidence.EXTRACTED, test, 9),
                GraphEdge.of("release_publish", "info_name", Relations.CALLS, Confidence.EXTRACTED, rel, 10),
                GraphEdge.of("release_publish", "info_name", Relations.CALLS, Confidence.EXTRACTED, rel, 12),
                GraphEdge.of("plan_label", "info_name", Relations.CALLS, Confidence.EXTRACTED, rel, 21),
                GraphEdge.of("cli_run", "release_publish", Relations.CALLS, Confidence.EXTRACTED, cli, 6),
                GraphEdge.of("infotest_names", "info_name", Relations.CALLS, Confidence.EXTRACTED, test, 10));
        return CodeGraph.merge(List.of(new Fragment(nodes, edges)));
    }

    private static GraphNode type(String id, String label, String qualified, String file) {
        return new GraphNode(
                id, label, "code", file, "L1", Map.of(Linker.QUALIFIED_NAME, qualified, "jk", Map.of("kind", "class")));
    }

    @Test
    void a_member_is_shown_with_its_owner_and_a_nested_owner_with_its_outer_type() {
        CodeGraph g = release();
        assertThat(g.display("plan_label")).isEqualTo("Release.Plan.label()");
        assertThat(g.display("plan")).isEqualTo("Release.Plan");
        assertThat(g.display("info_name")).isEqualTo("Info.name()");
        assertThat(new GraphTools(g).neighbors("Info.name", "calls", 2000))
                .as("a caller in a nested record reads as that record's, not the file's top-level class")
                .contains("<-calls- Release.Plan.label()  src/main/java/a/Release.java:21")
                .contains("<-calls- Release.publish()  src/main/java/a/Release.java:10,12");
    }

    @Test
    void callers_are_counted_split_main_from_test_and_said_to_be_complete() {
        String out = new GraphTools(release()).callers("Info.name", 1, 2000);
        assertThat(out)
                .startsWith(
                        "Callers of Info.name(): 2 in main sources, 1 in tests.\nComplete from the compiled classes")
                .contains("main:\n  Release.Plan.label()  src/main/java/a/Release.java:21\n"
                        + "  Release.publish()  src/main/java/a/Release.java:10,12\n"
                        + "test:\n  InfoTest.names()  src/test/java/a/InfoTest.java:10")
                .as("direct callers only: the caller's caller waits for depth > 1")
                .doesNotContain("Cli.run()");
    }

    @Test
    void a_walk_says_whether_it_reached_every_caller_or_the_hop_limit_stopped_it() {
        assertThat(new GraphTools(release()).callers("Info.name", 2, 2000))
                .as("the limit reached exactly where the chain ends is not a cut")
                .contains(", every one: the chain ends after 2 hops.");
        assertThat(new GraphTools(dispatch()).callers("Spec.home", 2, 2000))
                .contains(", every one: the chain ends after 2 hops.");
        assertThat(new GraphTools(release()).callers("Cli.run", 1, 2000)).doesNotContain("hops");
        String cut = new GraphTools(chain()).callers("C.c", 2, 2000);
        assertThat(cut)
                .startsWith(
                        "Every method that reaches C.c(): 2 in main sources, 0 in tests, stopped at 2 hops: more lie"
                                + " beyond, raise max_hops (-L) for them.")
                .doesNotContain("A.a()");
    }

    /** {@code A.a} calls {@code B.b} calls {@code BB.bb} calls {@code C.c}: three hops. */
    private static CodeGraph chain() {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        String[][] chain = {{"a", "A"}, {"b", "B"}, {"bb", "BB"}, {"c", "C"}};
        for (String[] t : chain) {
            String file = "src/main/java/x/" + t[1] + ".java";
            nodes.add(type(t[0], t[1], "x." + t[1], file));
            nodes.add(new GraphNode(t[0] + "_m", "." + t[0] + "()", "code", file, "L3"));
            edges.add(GraphEdge.of(t[0], t[0] + "_m", Relations.METHOD, Confidence.EXTRACTED, file, 3));
        }
        for (int i = 0; i + 1 < chain.length; i++) {
            String file = "src/main/java/x/" + chain[i][1] + ".java";
            edges.add(GraphEdge.of(
                    chain[i][0] + "_m", chain[i + 1][0] + "_m", Relations.CALLS, Confidence.EXTRACTED, file, 4));
        }
        return CodeGraph.merge(List.of(new Fragment(nodes, edges)));
    }

    @Test
    void transitive_callers_list_every_method_that_reaches_it_by_hop_with_the_step_taken() {
        String out = new GraphTools(release()).callers("Info.name", 8, 2000);
        assertThat(out)
                .startsWith(
                        "Every method that reaches Info.name(): 3 in main sources, 1 in tests, every one: the chain ends"
                                + " after 2 hops.")
                .contains("  hop 1 (calls it directly):\n  Release.Plan.label()")
                .contains("  hop 2:\n  Cli.run()  src/main/java/a/Cli.java:6  -> Release.publish()");
        assertThat(new GraphTools(release()).callers("Cli.run", 8, 2000))
                .endsWith("Nothing in the compiled classes calls it.");
    }

    @Test
    void callers_of_a_method_the_graph_does_not_have_says_so_instead_of_answering_for_a_near_name() {
        assertThat(new GraphTools(release()).callers("Main.main", 1, 2000))
                .startsWith("No method 'Main.main'.")
                .doesNotContain("Callers of");
        assertThat(new GraphTools(release()).callers("Info.nmae", 1, 2000))
                .startsWith("'Info' (src/main/java/a/Info.java) declares no member 'nmae'; its members: name");
    }

    /** {@code Step.run} calls {@code Exec.home} only; {@code Spec.home} implements it, and {@code Cli.main} calls run. */
    private static CodeGraph dispatch() {
        String exec = "src/main/java/a/Exec.java";
        String spec = "src/main/java/a/Spec.java";
        String step = "src/main/java/a/Step.java";
        List<GraphNode> nodes = List.of(
                type("exec", "Exec", "a.Exec", exec),
                new GraphNode("exec_home", ".home()", "code", exec, "L4"),
                type("spec", "Spec", "a.Spec", spec),
                new GraphNode("spec_home", ".home()", "code", spec, "L6"),
                type("step", "Step", "a.Step", step),
                new GraphNode("step_run", ".run()", "code", step, "L5"),
                new GraphNode("step_main", ".main()", "code", step, "L9"));
        List<GraphEdge> edges = List.of(
                GraphEdge.of("exec", "exec_home", Relations.METHOD, Confidence.EXTRACTED, exec, 4),
                GraphEdge.of("spec", "spec_home", Relations.METHOD, Confidence.EXTRACTED, spec, 6),
                GraphEdge.of("step", "step_run", Relations.METHOD, Confidence.EXTRACTED, step, 5),
                GraphEdge.of("step", "step_main", Relations.METHOD, Confidence.EXTRACTED, step, 9),
                GraphEdge.of("spec_home", "exec_home", Relations.OVERRIDES, Confidence.EXTRACTED, spec, 6),
                GraphEdge.of("step_run", "exec_home", Relations.CALLS, Confidence.EXTRACTED, step, 6),
                GraphEdge.of("step_main", "step_run", Relations.CALLS, Confidence.EXTRACTED, step, 10));
        return CodeGraph.merge(List.of(new Fragment(nodes, edges)));
    }

    @Test
    void a_call_to_the_interface_method_an_implementation_overrides_counts_as_reaching_it() {
        assertThat(new GraphTools(dispatch()).callers("Spec.home", 1, 2000))
                .startsWith("Callers of Spec.home(): 1 in main sources, 0 in tests.")
                .contains("  Step.run()  src/main/java/a/Step.java:6  through Exec.home()");
        assertThat(new GraphTools(dispatch()).callers("Spec.home", 8, 2000))
                .contains("  hop 2:\n  Step.main()  src/main/java/a/Step.java:10  -> Step.run()");
        assertThat(new GraphTools(dispatch()).callers("Exec.home", 1, 2000))
                .as("the interface's own callers are not marked: they call it by name")
                .contains("  Step.run()  src/main/java/a/Step.java:6")
                .doesNotContain("  through ");
    }

    /**
     * {@code Tool.tool(A)} calls {@code X.x}, {@code Tool.tool(B)} does not; {@code P.p} calls only
     * {@code tool(B)}, {@code Q.q} calls {@code tool(A)}. {@code Impl.m(A)} overrides {@code Iface.m(A)};
     * {@code R.r} calls {@code Iface.m(B)}, an overload {@code Impl} does not override, {@code S.s} calls
     * {@code Iface.m(A)}. {@code tool(C)} delegates to {@code tool(A)}, and {@code U.u} calls {@code tool(C)}.
     */
    private static CodeGraph overloads() {
        String f = "src/main/java/o/O.java";
        List<GraphNode> nodes = new ArrayList<>();
        for (String[] t : new String[][] {
            {"x", "X", "x"},
            {"tool", "Tool", "tool"},
            {"p", "P", "p"},
            {"q", "Q", "q"},
            {"iface", "Iface", "m"},
            {"impl", "Impl", "m"},
            {"r", "R", "r"},
            {"s", "S", "s"},
            {"u", "U", "u"}
        }) {
            nodes.add(type(t[0], t[1], "o." + t[1], f));
            Map<String, Object> extra = t[0].equals("tool") ? Map.of(Linker.DELEGATES, List.of("(C)>(A)")) : Map.of();
            nodes.add(new GraphNode(t[0] + "_m", "." + t[2] + "()", "code", f, "L2", extra));
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (GraphNode n : nodes) {
            if (n.id().endsWith("_m")) {
                edges.add(GraphEdge.of(n.id().replace("_m", ""), n.id(), Relations.METHOD, Confidence.EXTRACTED, f, 2));
            }
        }
        edges.add(call("tool_m", "x_m", "(A)>()"));
        edges.add(call("p_m", "tool_m", "()>(B)"));
        edges.add(call("q_m", "tool_m", "()>(A)"));
        edges.add(call("u_m", "tool_m", "()>(C)"));
        edges.add(GraphEdge.of("impl_m", "iface_m", Relations.OVERRIDES, Confidence.EXTRACTED, f, 2)
                .withOverload("(A)>(A)"));
        edges.add(call("r_m", "iface_m", "()>(B)"));
        edges.add(call("s_m", "iface_m", "()>(A)"));
        return CodeGraph.merge(List.of(new Fragment(nodes, edges)));
    }

    private static GraphEdge call(String from, String to, String overload) {
        return GraphEdge.of(from, to, Relations.CALLS, Confidence.EXTRACTED, "src/main/java/o/O.java", 5)
                .withOverload(overload);
    }

    @Test
    void callers_follow_the_overloads_a_call_joins_not_every_method_of_the_name() {
        assertThat(new GraphTools(overloads()).callers("X.x", 8, 2000))
                .as("tool(A) reaches X; P calls tool(B), which does not")
                .contains("Tool.tool()", "Q.q()")
                .as("tool(C) delegates to tool(A), so U, which calls tool(C), reaches X too")
                .contains("U.u()")
                .doesNotContain("P.p()");
        assertThat(new GraphTools(overloads()).callers("Impl.m", 8, 2000))
                .as("a call to Iface.m(B) never dispatches to Impl.m(A)")
                .contains("S.s()  src/main/java/o/O.java:5  through Iface.m()")
                .doesNotContain("R.r()");
    }

    @Test
    void a_direct_answer_whose_callers_are_called_in_turn_points_at_transitive() {
        assertThat(new GraphTools(release()).callers("Info.name", 1, 2000))
                .as("Release.publish is called by Cli.run; Release.Plan.label and InfoTest.names are not called")
                .endsWith("1 of them is called in turn: transitive=true (-t) lists every method that reaches it in one"
                        + " answer, instead of a callers call per method.");
        assertThat(new GraphTools(release()).callers("Release.publish", 1, 2000))
                .as("Cli.run, its one caller, is called by nothing")
                .doesNotContain("called in turn");
        assertThat(new GraphTools(release()).callers("Info.name", 8, 2000))
                .as("a transitive answer already holds the chain")
                .doesNotContain("called in turn");
    }
}
