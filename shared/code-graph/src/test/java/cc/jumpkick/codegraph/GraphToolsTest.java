// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
    void neighbors_of_an_overloaded_member_lists_every_overload() {
        String out = new GraphTools(shop()).neighbors("OrderService.total", "calls", 2000);
        assertThat(out)
                .contains("Neighbors of .total() [src/shop/OrderService.java:10]:")
                .contains("Neighbors of .total() [src/shop/OrderService.java:20]:")
                .contains("<-- .checkout() [calls]")
                .contains("<-- .invoice() [calls]")
                .doesNotContain("Cart");
        assertThat(new GraphTools(shop()).neighbors("Cart.total", "calls", 2000))
                .startsWith("Neighbors of .total():")
                .contains("<-- .checkout() [calls]")
                .doesNotContain("invoice");
    }
}
