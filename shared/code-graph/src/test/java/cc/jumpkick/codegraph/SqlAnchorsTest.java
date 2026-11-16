// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.codegraph.extract.Extractor;
import cc.jumpkick.codegraph.extract.Extractors;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * SQL tables and columns: Flyway migrations, a Liquibase XML and a YAML changelog define, alter and
 * drop them, declared; DDL's indexes and foreign keys use them, declared; a report query, a seed and
 * a MyBatis mapper name them, inferred, or ambiguous for a bare column two of the query's tables
 * hold; and a name no DDL defines links nowhere.
 */
class SqlAnchorsTest {

    private static final String V1 = "src/main/resources/db/migration/V1__schema.sql";
    private static final String V2 = "src/main/resources/db/migration/V2__rename.sql";

    private static Fragment extract(String rel, String text) {
        Extractor x = Objects.requireNonNull(Extractors.forPath(rel));
        return x.extract(rel, text);
    }

    private static CodeGraph shop() {
        List<Fragment> f = new ArrayList<>();
        f.add(extract(V1, """
                -- orders and the customers who place them
                CREATE TABLE customers (
                    id    uuid PRIMARY KEY,
                    email text NOT NULL /* unique per tenant */
                );

                CREATE TABLE IF NOT EXISTS public.orders (
                    id          uuid PRIMARY KEY,
                    status      text NOT NULL DEFAULT 'new',
                    customer_id uuid NOT NULL REFERENCES customers (id),
                    CONSTRAINT status_known CHECK (status IN ('new', 'paid'))
                );

                CREATE INDEX orders_by_status ON orders (status);
                """));
        f.add(extract(V2, """
                ALTER TABLE orders RENAME COLUMN status TO state;
                ALTER TABLE customers ADD COLUMN phone text, DROP COLUMN email;
                """));
        f.add(extract("reports/daily.sql", """
                SELECT o.state, count(*)
                FROM orders o
                JOIN customers c ON c.id = o.customer_id
                WHERE state <> 'void' AND nowhere = 1
                GROUP BY o.state;
                """));
        f.add(extract("src/main/resources/db/seed/V3__seed.sql", """
                INSERT INTO customers (id, phone) VALUES ('c1', '555');
                SELECT id FROM orders, customers;
                """));
        f.add(extract("src/main/resources/db/changelog/invoices.xml", """
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                  <changeSet id="1" author="a">
                    <createTable tableName="invoices">
                      <column name="id" type="uuid"/>
                      <column name="total" type="numeric"/>
                    </createTable>
                  </changeSet>
                  <changeSet id="2" author="a">
                    <renameColumn tableName="invoices" oldColumnName="total" newColumnName="amount"/>
                  </changeSet>
                </databaseChangeLog>
                """));
        f.add(extract("src/main/resources/db/changelog/payments.yaml", """
                databaseChangeLog:
                  - changeSet:
                      id: 1
                      changes:
                        - createTable:
                            tableName: payments
                            columns:
                              - column:
                                  name: id
                                  type: uuid
                              - column:
                                  name: method
                                  type: text
                  - changeSet:
                      id: 2
                      changes:
                        - dropColumn:
                            tableName: payments
                            columnName: method
                """));
        f.add(extract("src/main/resources/mappers/InvoiceMapper.xml", """
                <mapper namespace="com.acme.InvoiceMapper">
                  <select id="amount" resultType="long">
                    select amount from invoices
                    <where> id = #{id} </where>
                  </select>
                </mapper>
                """));
        CodeGraph g = Linker.link(f);
        g.modules(new Modules(List.of(new Modules.Module("", false))));
        return g;
    }

    private static List<GraphEdge> edges(CodeGraph g, String anchor, String relation) {
        return g.incident(anchor).stream()
                .filter(e -> e.target().equals(anchor) && e.relation().equals(relation))
                .toList();
    }

    @Test
    void migrationsDefineRenameAndDropAndDdlUsesAreDeclared() {
        CodeGraph g = shop();
        assertThat(edges(g, "column:orders.status", Relations.DEFINES))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation, GraphEdge::confidence)
                .containsExactly(tuple(V1, "L9", Confidence.DECLARED));
        assertThat(edges(g, "column:orders.status", Relations.DROPS))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation)
                .containsExactly(tuple(V2, "L1"));
        assertThat(edges(g, "column:orders.status", Relations.USES))
                .as("the index and the check name it; the check is not read, the index is")
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation, GraphEdge::confidence)
                .containsExactly(tuple(V1, "L14", Confidence.DECLARED));
        assertThat(edges(g, "column:orders.state", Relations.DEFINES))
                .extracting(GraphEdge::sourceFile)
                .containsExactly(V2);
        assertThat(edges(g, "column:customers.id", Relations.USES))
                .as("a foreign key's referenced column")
                .extracting(GraphEdge::sourceFile, GraphEdge::confidence)
                .contains(tuple(V1, Confidence.DECLARED));
        assertThat(g.has("column:orders.status_known"))
                .as("a constraint is no column")
                .isFalse();
        assertThat(edges(g, "table:orders", Relations.DEFINES))
                .as("public. is the default schema")
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L7");
        assertThat(edges(g, "column:customers.email", Relations.DROPS))
                .extracting(GraphEdge::sourceFile)
                .containsExactly(V2);
        assertThat(edges(g, "column:customers.phone", Relations.DEFINES))
                .extracting(GraphEdge::sourceFile)
                .containsExactly(V2);
    }

    @Test
    void liquibaseXmlAndYamlChangelogsDefineAndDrop() {
        CodeGraph g = shop();
        assertThat(edges(g, "column:invoices.total", Relations.DEFINES))
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L5");
        assertThat(edges(g, "column:invoices.total", Relations.DROPS))
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L9");
        assertThat(edges(g, "column:invoices.amount", Relations.DEFINES))
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L9");
        assertThat(edges(g, "column:payments.method", Relations.DEFINES))
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L12");
        assertThat(edges(g, "column:payments.method", Relations.DROPS))
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L19");
        assertThat(edges(g, "table:payments", Relations.DEFINES)).hasSize(1);
    }

    @Test
    void queriesNameTablesAndColumnsInferredAndABareColumnTwoTablesHoldIsAmbiguous() {
        CodeGraph g = shop();
        assertThat(edges(g, "column:orders.state", Relations.USES))
                .as("one edge from the file, at every line naming it, the bare one included")
                .extracting(GraphEdge::sourceFile, GraphEdge::sites, GraphEdge::confidence)
                .containsExactly(tuple("reports/daily.sql", List.of("L1", "L4", "L5"), Confidence.INFERRED));
        assertThat(edges(g, "column:orders.customer_id", Relations.USES))
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L3");
        assertThat(edges(g, "column:customers.phone", Relations.USES))
                .extracting(GraphEdge::sourceFile, GraphEdge::confidence)
                .containsExactly(tuple("src/main/resources/db/seed/V3__seed.sql", Confidence.INFERRED));
        assertThat(edges(g, "column:invoices.amount", Relations.USES))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation)
                .containsExactly(tuple("src/main/resources/mappers/InvoiceMapper.xml", "L3"));
        assertThat(edges(g, "column:orders.id", Relations.USES))
                .filteredOn(e -> e.sourceFile().endsWith("V3__seed.sql"))
                .extracting(GraphEdge::confidence)
                .containsExactly(Confidence.AMBIGUOUS);
        assertThat(g.nodes()).noneMatch(n -> n.id().contains("nowhere"));
    }

    @Test
    void aColumnsAnswerNamesWhereItWasDefinedDroppedAndUsed() {
        String answer = new GraphTools(shop()).callers("column:orders.status", 1, 2000);
        assertThat(answer)
                .startsWith("column:orders.status: 1 definition, 1 use (1 declared).")
                .contains(
                        "defined:\n  src/main/resources/db/migration/V1__schema.sql:9\n",
                        "main:\n  src/main/resources/db/migration/V1__schema.sql:14\n",
                        "dropped or renamed away:\n  src/main/resources/db/migration/V2__rename.sql:1");
        assertThat(new GraphTools(shop()).callers("column:payments.id", 1, 2000))
                .as("defined and used by nothing: unused, and no claim that the search was complete")
                .contains("Nothing uses it.")
                .doesNotContain("omplete");
    }
}
