// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Linker;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ScriptFactsTest {

    /** What oxc-facts.cjs printed for an Angular component importing a service through a tsconfig alias. */
    private static final String COMPONENT = """
            {"file":"src/app/cart/cart.component.ts","errors":0,\
            "decls":[{"name":"CartComponent","kind":"class","line":6,"parent":-1,"selector":"app-cart"},\
            {"name":"label","kind":"method","line":8,"parent":0}],\
            "imports":[{"spec":"@angular/core","resolved":null,"line":1,"kind":"static",\
            "names":[{"local":"Component","imported":"Component"}]},\
            {"spec":"@app/services/cart.service","resolved":"src/app/services/cart.service.ts","line":2,"kind":"static",\
            "names":[{"local":"CartService","imported":"CartService"},{"local":"formatTotal","imported":"formatTotal"}]}],\
            "calls":[{"from":1,"callee":"formatTotal","object":null,"line":8}],\
            "uses":[{"from":0,"kind":"file","name":"./cart.component.html","line":5}],\
            "notes":[{"text":"NOTE: re-prices on every change","line":4}]}""";

    private static final String SERVICE = """
            {"file":"src/app/services/cart.service.ts","errors":0,\
            "decls":[{"name":"CartService","kind":"class","line":1,"parent":-1},\
            {"name":"total","kind":"method","line":2,"parent":0},\
            {"name":"formatTotal","kind":"function","line":4,"parent":-1}],\
            "imports":[],"calls":[],"uses":[],"notes":[]}""";

    @Test
    void importedCallsLinkToTheDeclarationInTheResolvedFile() {
        Fragment component = Objects.requireNonNull(ScriptFacts.fragment(COMPONENT));
        Fragment service = Objects.requireNonNull(ScriptFacts.fragment(SERVICE));
        CodeGraph g = Linker.link(List.of(component, service));
        String label = "src_app_cart_cart_component_cartcomponent_label";
        String format = "src_app_services_cart_service_formattotal";
        assertThat(g.node(label)).extracting(GraphNode::label).isEqualTo(".label()");
        assertThat(g.edges())
                .anyMatch(e -> e.source().equals(label)
                        && e.target().equals(format)
                        && e.relation().equals("calls"));
        assertThat(g.edges())
                .anyMatch(e -> e.source().equals("src_app_cart_cart_component")
                        && e.target().equals("src_app_services_cart_service")
                        && e.relation().equals("imports_from"));
        assertThat(g.node("npm_angular_core")).isNotNull();
        assertThat(g.node("src_app_cart_cart_component_cartcomponent"))
                .satisfies(n ->
                        assertThat(String.valueOf(n.extra().get("attributes"))).contains("app-cart"));
        assertThat(g.nodes()).anyMatch(n -> n.fileType().equals("rationale"));
    }

    @Test
    void aFileTheScriptCouldNotReadHasNoFragment() {
        assertThat(ScriptFacts.fragment("{\"file\":\"a.ts\",\"failed\":\"boom\"}"))
                .isNull();
    }
}
