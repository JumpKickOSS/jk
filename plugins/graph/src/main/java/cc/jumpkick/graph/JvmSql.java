// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.codegraph.extract.Sql;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.FieldRef;
import cc.jumpkick.guard.facts.MethodFacts;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * SQL tables and columns in compiled classes. A JPA entity uses its table and its fields' columns:
 * declared where {@code @Table}, {@code @Column} or {@code @JoinColumn} names them, inferred under
 * the default naming (snake case, Spring's strategy). jOOQ's generated table classes are read for
 * the table and columns each stands for, checked against the names the generator wrote into them,
 * so code reading {@code HOTEL.SLUG} is a bound use of {@code hotel.slug}. A string literal that
 * reads as a query, and a native {@code @Query} or MyBatis statement annotation, names tables and
 * columns as {@link Sql#uses} reads them, inferred.
 */
final class JvmSql {

    private JvmSql() {}

    private static final List<String> JPA = List.of("jakarta.persistence.", "javax.persistence.");
    private static final Set<String> NOT_COLUMNS =
            Set.of("Transient", "OneToMany", "ManyToMany", "Embedded", "EmbeddedId", "ElementCollection");
    private static final Set<String> MYBATIS = Set.of(
            "org.apache.ibatis.annotations.Select",
            "org.apache.ibatis.annotations.Insert",
            "org.apache.ibatis.annotations.Update",
            "org.apache.ibatis.annotations.Delete");
    private static final String JOOQ_TABLE = "org/jooq/impl/TableImpl";
    private static final String JOOQ_FIELD = "Lorg/jooq/TableField;";
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_TRANSIENT = 0x0080;
    private static final int ACC_SYNTHETIC = 0x1000;

    /** The tables jOOQ generated classes stand for, by internal name, and each one's columns by field name. */
    record Jooq(Map<String, String> tables, Map<String, Map<String, String>> columns) {
        static final Jooq NONE = new Jooq(Map.of(), Map.of());
    }

    /**
     * Every jOOQ table class in {@code index}: a {@code TableImpl} subclass whose snake-cased simple
     * name is one of the names it passes {@code DSL.name}, and as its columns the {@code TableField}
     * fields whose lower-cased name is another.
     */
    static Jooq jooq(FactsIndex index) {
        Map<String, String> tables = new HashMap<>();
        Map<String, Map<String, String>> columns = new HashMap<>();
        for (ClassFacts c : index.classList()) {
            if (!JOOQ_TABLE.equals(c.superName())) continue;
            Set<String> names = new HashSet<>();
            for (MethodFacts m : c.methods()) {
                for (CallSite s : m.calls()) {
                    if (s.owner().equals("org/jooq/impl/DSL") && s.name().equals("name")) names.addAll(s.literals());
                }
            }
            String simple = c.name().substring(c.name().lastIndexOf('/') + 1);
            String table = snake(simple);
            if (!names.contains(table)) continue;
            Map<String, String> fields = new HashMap<>();
            for (FieldFacts f : c.fields()) {
                String column = f.name().toLowerCase(Locale.ROOT);
                if ((f.access() & ACC_STATIC) == 0 && f.desc().equals(JOOQ_FIELD) && names.contains(column)) {
                    fields.put(f.name(), column);
                }
            }
            tables.put(c.name(), table);
            columns.put(c.name(), fields);
        }
        return tables.isEmpty() ? Jooq.NONE : new Jooq(Map.copyOf(tables), Map.copyOf(columns));
    }

    /** A JPA entity's table and its persistent fields' columns, as uses from the entity's node. */
    static void entity(
            ClassFacts c,
            String from,
            String rel,
            Map<String, GraphNode> nodes,
            List<GraphEdge> edges,
            List<Mention> mentions) {
        if (annotation(c.annotations(), "Entity") == null) return;
        AnnotationFacts t = annotation(c.annotations(), "Table");
        String declared = t == null ? null : first(t.values().get("name"));
        String simple =
                c.name().substring(Math.max(c.name().lastIndexOf('/'), c.name().lastIndexOf('$')) + 1);
        String table = declared != null && !declared.isEmpty() ? declared : snake(simple);
        if (declared != null && !declared.isEmpty()) {
            anchor(nodes, edges, from, Anchors.TABLE, table, rel, null);
        } else {
            mentions.add(new Mention(from, Mention.Kind.TABLE, table, 0));
        }
        for (FieldFacts f : c.fields()) {
            if ((f.access() & (ACC_STATIC | ACC_TRANSIENT | ACC_SYNTHETIC)) != 0) continue;
            if (f.annotations().stream().anyMatch(a -> jpa(a) && NOT_COLUMNS.contains(simple(a)))) continue;
            AnnotationFacts col = annotation(f.annotations(), "Column");
            AnnotationFacts naming = col != null ? col : annotation(f.annotations(), "JoinColumn");
            boolean relation =
                    annotation(f.annotations(), "ManyToOne") != null || annotation(f.annotations(), "OneToOne") != null;
            String named = naming == null ? null : first(naming.values().get("name"));
            String context = "field " + f.name();
            if (named != null && !named.isEmpty()) {
                anchor(nodes, edges, from, Anchors.COLUMN, table + "." + named, rel, context);
            } else {
                String column = snake(f.name()) + (relation ? "_id" : "");
                mentions.add(new Mention(from, Mention.Kind.COLUMN, table + "." + column, 0, Relations.USES, context));
            }
        }
    }

    /**
     * A method's SQL: its reads of jOOQ table and field constants as bound uses, a native {@code
     * @Query} or MyBatis statement on it and every query-shaped literal it loads as mentions.
     */
    static void method(
            MethodFacts m,
            String from,
            String rel,
            Jooq jooq,
            Map<String, GraphNode> nodes,
            List<GraphEdge> edges,
            List<Mention> mentions) {
        if (!jooq.tables().isEmpty()) {
            for (FieldRef f : m.fieldRefs()) {
                // The generated class assigns its own constants; code using the table reads them.
                if (f.write()) continue;
                Map<String, String> cols = jooq.columns().get(f.owner());
                String column = cols == null ? null : cols.get(f.name());
                if (column != null) {
                    bound(
                            nodes,
                            edges,
                            from,
                            Anchors.COLUMN,
                            jooq.tables().get(f.owner()) + "." + column,
                            rel,
                            f.line());
                } else if (f.desc().startsWith("L") && f.desc().endsWith(";")) {
                    String table =
                            jooq.tables().get(f.desc().substring(1, f.desc().length() - 1));
                    if (table != null) bound(nodes, edges, from, Anchors.TABLE, table, rel, f.line());
                }
            }
        }
        for (AnnotationFacts a : m.annotations()) {
            boolean nativeQuery = a.typeName().endsWith(".Query")
                    && a.values().getOrDefault("nativeQuery", List.of()).contains("true");
            if (!nativeQuery && !MYBATIS.contains(a.typeName())) continue;
            for (String q : a.values().getOrDefault("value", List.of())) query(q, from, m.firstLine(), mentions);
        }
        for (CallSite s : m.calls()) {
            for (String literal : s.literals()) query(literal, from, s.line(), mentions);
        }
    }

    private static void query(String text, String from, int line, List<Mention> mentions) {
        if (!Sql.isQuery(text)) return;
        for (Sql.Use u : Sql.uses(text)) mentions.add(new Mention(from, u.kind(), u.text(), line));
    }

    private static void anchor(
            Map<String, GraphNode> nodes,
            List<GraphEdge> edges,
            String from,
            String kind,
            String name,
            String rel,
            @Nullable String context) {
        GraphNode n = Anchors.node(kind, name);
        nodes.merge(n.id(), n, Anchors::preferred);
        GraphEdge e = GraphEdge.of(from, n.id(), Relations.USES, Confidence.DECLARED, rel, 0);
        edges.add(context == null ? e : e.withContext(context));
    }

    private static void bound(
            Map<String, GraphNode> nodes,
            List<GraphEdge> edges,
            String from,
            String kind,
            String name,
            String rel,
            int line) {
        GraphNode n = Anchors.node(kind, name);
        nodes.merge(n.id(), n, Anchors::preferred);
        edges.add(GraphEdge.of(from, n.id(), Relations.USES, Confidence.EXTRACTED, rel, line));
    }

    private static @Nullable AnnotationFacts annotation(List<AnnotationFacts> annotations, String simple) {
        for (AnnotationFacts a : annotations) if (jpa(a) && simple(a).equals(simple)) return a;
        return null;
    }

    private static boolean jpa(AnnotationFacts a) {
        return JPA.stream().anyMatch(p -> a.typeName().startsWith(p));
    }

    private static String simple(AnnotationFacts a) {
        return a.typeName().substring(a.typeName().lastIndexOf('.') + 1);
    }

    private static @Nullable String first(@Nullable List<String> values) {
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /** {@code OrderLine} and {@code orderLine} as {@code order_line}, as Spring's default naming writes them. */
    static String snake(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(name.charAt(i - 1))) out.append('_');
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }
}
