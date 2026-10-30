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
        /** A configuration key, {@code server.port}, or every key under a prefix, {@code shop.*}. */
        CONFIG_KEY,
        /** A module specifier already resolved to a repo-relative path by the extractor. */
        MODULE
    }
}
