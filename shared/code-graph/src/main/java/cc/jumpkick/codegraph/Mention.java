// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import org.jspecify.annotations.Nullable;

/**
 * A reference an extractor saw but cannot resolve alone: the linker turns it into a {@code
 * relation} edge from {@code source} once every file's nodes are known, or drops it. {@code line}
 * is 1-based, 0 when unknown; {@code context} narrows the edge ({@code call}, {@code field}, …).
 * {@code overload} is {@code from>to}, the JVM descriptors of the two methods a compiled call or
 * override joins ({@code *} for one whose overload is not known), and null otherwise.
 */
public record Mention(
        String source,
        Kind kind,
        String text,
        int line,
        String relation,
        @Nullable String context,
        @Nullable String overload) {

    /** A mention whose edge takes the kind's usual relation and no context. */
    public Mention(String source, Kind kind, String text, int line) {
        this(source, kind, text, line, defaultRelation(kind), null, null);
    }

    public Mention(String source, Kind kind, String text, int line, String relation) {
        this(source, kind, text, line, relation, null, null);
    }

    public Mention(String source, Kind kind, String text, int line, String relation, @Nullable String context) {
        this(source, kind, text, line, relation, context, null);
    }

    private static String defaultRelation(Kind kind) {
        return switch (kind) {
            case MODULE -> Relations.IMPORTS_FROM;
            case MEMBER -> Relations.CALLS;
            case CONFIG_KEY, ROUTE, ROUTE_CALL, TABLE, COLUMN, ENV, PORT -> Relations.USES;
            default -> Relations.REFERENCES;
        };
    }

    /** What {@code text} names. */
    public enum Kind {
        /** A JVM class by binary name, {@code com.acme.Shop} or {@code com.acme.Shop$Cart}. */
        CLASS,
        /** A JVM method, {@code com.acme.Shop#total}: found on the class or the nearest supertype declaring it. */
        MEMBER,
        /** A bare symbol name from prose, {@code OrderService}: matched to a code node's label. */
        SYMBOL,
        /** A file path relative to the mentioning file's directory, or to the root with a leading {@code /}. */
        FILE,
        /**
         * A configuration key, {@code server.port}, or every key under a prefix, {@code shop.*}: resolved
         * to a {@code config} anchor only when some file names it, and always inferred.
         */
        CONFIG_KEY,
        /** A module specifier already resolved to a repo-relative path by the extractor. */
        MODULE,
        /**
         * An HTTP request, {@code GET /orders/42} ({@code *} for a method not known): resolved to the
         * route anchors whose paths it fits, preferring the most literal segments, always inferred.
         */
        ROUTE,
        /**
         * An HTTP request a client or test call makes with a literal path and the method its name
         * gives ({@code MockMvc get("/orders/{id}")}): declared when it is a route's own method and
         * template, inferred when it only fits one.
         */
        ROUTE_CALL,
        /** A SQL table a query names, {@code orders}: resolved only to a table some DDL names, inferred. */
        TABLE,
        /**
         * A SQL column a query names: {@code orders.status} on its table, or {@code status@orders,items}
         * on whichever of the query's tables has it, inferred for one and ambiguous for several.
         */
        COLUMN,
        /** An environment variable a script or YAML value reads, {@code $DATABASE_URL}: linked only to one something sets or reads, inferred. */
        ENV,
        /** A port a URL names, {@code localhost:8080}: linked only to a port something defines or uses, inferred. */
        PORT
    }
}
