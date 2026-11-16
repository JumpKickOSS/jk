// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphTools;
import cc.jumpkick.codegraph.Relations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiled classes reach SQL anchors: a JPA entity's explicit names are declared uses and its
 * default ones inferred, a transient field and a collection none; code reading a jOOQ generated
 * field is a bound use of its column; a query in a string literal names its columns, inferred.
 */
class SqlGraphTest {

    private static Map<String, String> files() {
        Map<String, String> f = new TreeMap<>();
        for (String a :
                new String[] {"Entity", "Table", "Column", "Transient", "OneToMany", "ManyToOne", "JoinColumn"}) {
            f.put("src/main/java/jakarta/persistence/" + a + ".java", """
                    package jakarta.persistence;
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface %s { String name() default ""; }
                    """.formatted(a));
        }
        f.put("src/main/java/org/jooq/TableField.java", "package org.jooq;\npublic interface TableField<R, T> {}\n");
        f.put("src/main/java/org/jooq/impl/DSL.java", """
                package org.jooq.impl;
                public final class DSL { public static Object name(String n) { return n; } }
                """);
        f.put("src/main/java/org/jooq/impl/TableImpl.java", """
                package org.jooq.impl;
                public class TableImpl<R> {
                    protected TableImpl(Object name) {}
                    protected <T> org.jooq.TableField<R, T> createField(Object name) { return null; }
                }
                """);
        f.put("src/main/java/com/acme/db/tables/CustomerOrder.java", """
                package com.acme.db.tables;

                import org.jooq.TableField;
                import org.jooq.impl.DSL;
                import org.jooq.impl.TableImpl;

                public class CustomerOrder extends TableImpl<Object> {
                    public static final CustomerOrder CUSTOMER_ORDER = new CustomerOrder();
                    public final TableField<Object, String> STATE = createField(DSL.name("state"));
                    public final TableField<Object, String> TOTAL_MINOR = createField(DSL.name("total_minor"));

                    private CustomerOrder() {
                        super(DSL.name("customer_order"));
                    }
                }
                """);
        f.put("src/main/java/com/acme/OrderEntity.java", """
                package com.acme;

                import jakarta.persistence.*;
                import java.util.List;

                @Entity
                @Table(name = "customer_order")
                public class OrderEntity {
                    @Column(name = "state")
                    String status;

                    Long totalMinor;

                    @Transient
                    String note;

                    @OneToMany
                    List<Object> lines;

                    @ManyToOne
                    OrderEntity parent;
                }
                """);
        f.put("src/main/java/com/acme/Reports.java", """
                package com.acme;

                import static com.acme.db.tables.CustomerOrder.CUSTOMER_ORDER;

                public class Reports {
                    Object states() {
                        return CUSTOMER_ORDER.STATE;
                    }

                    String totals(java.util.function.Function<String, String> jdbc) {
                        return jdbc.apply("select total_minor from customer_order where state = ?");
                    }
                }
                """);
        f.put("src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE customer_order (
                    id          bigint PRIMARY KEY,
                    state       text   NOT NULL,
                    total_minor bigint NOT NULL,
                    parent_id   bigint
                );
                """);
        return f;
    }

    @Test
    void entitiesJooqFieldsAndQueryLiteralsUseColumnsWithTheirGrades(@TempDir Path dir) throws IOException {
        CodeGraph g = AnchorGraphTest.graph(dir, files());
        List<GraphEdge> state = g.incident("column:customer_order.state").stream()
                .filter(e -> e.relation().equals(Relations.USES))
                .toList();
        assertThat(state)
                .extracting(e -> g.display(e.source()), GraphEdge::confidence, GraphEdge::context)
                .containsExactlyInAnyOrder(
                        tuple("OrderEntity", Confidence.DECLARED, "field status"),
                        tuple("Reports.states()", Confidence.EXTRACTED, null),
                        tuple("Reports.totals()", Confidence.INFERRED, null));
        assertThat(g.incident("column:customer_order.total_minor"))
                .filteredOn(e -> e.relation().equals(Relations.USES))
                .extracting(e -> g.display(e.source()), GraphEdge::confidence)
                .containsExactlyInAnyOrder(
                        tuple("OrderEntity", Confidence.INFERRED), tuple("Reports.totals()", Confidence.INFERRED));
        assertThat(g.incident("column:customer_order.parent_id"))
                .as("a many-to-one's default join column")
                .filteredOn(e -> e.relation().equals(Relations.USES))
                .extracting(GraphEdge::confidence)
                .containsExactly(Confidence.INFERRED);
        assertThat(g.nodes()).noneMatch(n -> n.id().endsWith(".note") || n.id().endsWith(".lines"));
        assertThat(new GraphTools(g).callers("column:customer_order.state", 1, 2000))
                .startsWith("column:customer_order.state: 1 definition, 3 uses (1 bound, 1 declared, 1 inferred).")
                .contains("  OrderEntity  src/main/java/com/acme/OrderEntity.java:", "  field status\n");
    }
}
