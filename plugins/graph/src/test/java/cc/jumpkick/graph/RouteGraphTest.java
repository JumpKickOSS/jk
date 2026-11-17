// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Relations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiled handlers define routes from their framework's annotations, a type's paths prefixing its
 * methods' (Spring, JAX-RS, Micronaut); a Spring {@code @HttpExchange} interface and a MockMvc call
 * use them, the latter also where the path is a constant loaded before another call of the
 * statement; a path built at run time and a JAX-RS sub-resource locator define nothing.
 */
class RouteGraphTest {

    private static Map<String, String> files() {
        Map<String, String> f = new TreeMap<>();
        String spring = "src/main/java/org/springframework/web/bind/annotation/";
        for (String a : new String[] {"RequestMapping", "GetMapping", "PostMapping"}) {
            f.put(spring + a + ".java", """
                    package org.springframework.web.bind.annotation;
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface %s {
                        String[] value() default {};
                        String[] path() default {};
                        %s
                    }
                    """.formatted(
                            a, a.equals("RequestMapping") ? "RequestMethod[] method() default {};" : ""));
        }
        f.put(spring + "RequestMethod.java", """
                package org.springframework.web.bind.annotation;
                public enum RequestMethod { GET, POST, PUT, DELETE }
                """);
        f.put("src/main/java/org/springframework/web/service/annotation/GetExchange.java", """
                package org.springframework.web.service.annotation;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface GetExchange { String value() default ""; }
                """);
        f.put("src/main/java/org/springframework/web/service/annotation/HttpExchange.java", """
                package org.springframework.web.service.annotation;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface HttpExchange { String value() default ""; }
                """);
        f.put("src/main/java/org/springframework/test/web/servlet/request/MockMvcRequestBuilders.java", """
                package org.springframework.test.web.servlet.request;
                public final class MockMvcRequestBuilders {
                    public static Object get(String uri, Object... vars) { return uri; }
                    public static Object post(String uri, Object... vars) { return uri; }
                }
                """);
        for (String a : new String[] {"Path", "GET", "DELETE"}) {
            f.put("src/main/java/jakarta/ws/rs/" + a + ".java", """
                    package jakarta.ws.rs;
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface %s { %s }
                    """.formatted(
                            a, a.equals("Path") ? "String value();" : ""));
        }
        for (String a : new String[] {"Controller", "Get"}) {
            f.put("src/main/java/io/micronaut/http/annotation/" + a + ".java", """
                    package io.micronaut.http.annotation;
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface %s { String value() default "/"; }
                    """.formatted(a));
        }
        f.put("src/main/java/com/acme/OrderController.java", """
                package com.acme;

                import org.springframework.web.bind.annotation.*;

                @RequestMapping("/api/orders")
                public class OrderController {
                    @GetMapping("/{id}")
                    public String one(String id) { return id; }

                    @PostMapping
                    public String place() { return ""; }

                    @RequestMapping(path = {"/a", "/b"}, method = RequestMethod.DELETE)
                    public void drop() {}

                    public String notMapped() { return "/api/orders/" + System.nanoTime(); }
                }
                """);
        f.put("src/main/java/com/acme/StockResource.java", """
                package com.acme;

                import jakarta.ws.rs.*;

                @Path("stock")
                public class StockResource {
                    @GET
                    public String all() { return ""; }

                    @DELETE
                    @Path("{sku}")
                    public void remove(String sku) {}

                    @Path("items")
                    public Object locator() { return null; }
                }
                """);
        f.put("src/main/java/com/acme/HealthController.java", """
                package com.acme;

                import io.micronaut.http.annotation.*;

                @Controller("/health")
                public class HealthController {
                    @Get
                    public String ok() { return "ok"; }
                }
                """);
        clients(f);
        return f;
    }

    /** The callers of the routes: an {@code @HttpExchange} interface and MockMvc calls in jhipster's shapes. */
    private static void clients(Map<String, String> f) {
        f.put("src/main/java/com/acme/OrderClient.java", """
                package com.acme;

                import org.springframework.web.service.annotation.*;

                @HttpExchange("/api/orders")
                public interface OrderClient {
                    @GetExchange("/{orderId}")
                    String fetch(String orderId);
                }
                """);
        f.put("src/main/java/com/acme/OrderCheck.java", """
                package com.acme;

                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

                public class OrderCheck {
                    private static final String ENTITY_API_URL = "/api/orders";
                    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";

                    Object one() {
                        return get("/api/orders/{id}", 7);
                    }

                    // jhipster's shape: the folded path is loaded before getId(), whose call takes it as its own literal.
                    Object byConstant(Order order) {
                        return get(ENTITY_API_URL_ID, order.getId());
                    }

                    private Order order = new Order();

                    // jhipster's own: the entity is a field, read between the path and the call.
                    Object byConstantOfAField() {
                        return get(ENTITY_API_URL_ID, order.getId());
                    }

                    Object other() {
                        return get("/api/orders/42");
                    }
                }
                """);
        f.put("src/main/java/com/acme/Order.java", """
                package com.acme;

                public class Order {
                    public Long getId() { return 1L; }
                }
                """);
    }

    private static Map<String, String> definers(CodeGraph g) {
        Map<String, String> out = new TreeMap<>();
        for (GraphEdge e : g.edges()) {
            if (e.relation().equals(Relations.DEFINES) && e.target().startsWith("route:")) {
                GraphNode n = g.node(e.source());
                out.put(e.target(), n == null ? e.source() : g.display(n.id()));
            }
        }
        return out;
    }

    @Test
    void handlersDefineRoutesFromTheirAnnotationsAndClientsUseThem(@TempDir Path dir) throws IOException {
        CodeGraph g = AnchorGraphTest.graph(dir, files());
        assertThat(definers(g))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "route:GET /api/orders/{}", "OrderController.one()",
                        "route:POST /api/orders", "OrderController.place()",
                        "route:DELETE /api/orders/a", "OrderController.drop()",
                        "route:DELETE /api/orders/b", "OrderController.drop()",
                        "route:GET /stock", "StockResource.all()",
                        "route:DELETE /stock/{}", "StockResource.remove()",
                        "route:GET /health", "HealthController.ok()"));
        assertThat(g.incident("route:GET /api/orders/{}"))
                .filteredOn(e -> e.relation().equals(Relations.USES))
                .extracting(e -> g.display(e.source()), GraphEdge::confidence, GraphEdge::context)
                .containsExactlyInAnyOrder(
                        tuple("OrderClient.fetch()", Confidence.DECLARED, "/api/orders/{orderId}"),
                        tuple("OrderCheck.one()", Confidence.DECLARED, "/api/orders/{id}"),
                        tuple("OrderCheck.byConstant()", Confidence.DECLARED, "/api/orders/{id}"),
                        tuple("OrderCheck.byConstantOfAField()", Confidence.DECLARED, "/api/orders/{id}"),
                        tuple("OrderCheck.other()", Confidence.INFERRED, "/api/orders/42"));
    }
}
