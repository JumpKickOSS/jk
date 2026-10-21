// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

/**
 * A reference an extractor saw but cannot resolve alone: the linker turns it into a {@code
 * relation} edge from {@code source} once every file's nodes are known, or drops it. {@code line}
 * is 1-based, 0 when unknown.
 */
public record Mention(String source, Kind kind, String text, int line, String relation) {

    /** A mention whose edge takes the kind's usual relation. */
    public Mention(String source, Kind kind, String text, int line) {
        this(source, kind, text, line, kind == Kind.MODULE ? Relations.IMPORTS_FROM : Relations.REFERENCES);
    }

    /** What {@code text} names. */
    public enum Kind {
        /** A JVM class by binary name, {@code com.acme.Shop} or {@code com.acme.Shop$Cart}. */
        CLASS,
        /** A bare symbol name from prose, {@code OrderService}: matched to a code node's label. */
        SYMBOL,
        /** A file path relative to the mentioning file's directory, or to the root with a leading {@code /}. */
        FILE,
        /** A configuration key, {@code server.port}: matched to a key a config file declares. */
        CONFIG_KEY,
        /** A module specifier already resolved to a repo-relative path by the extractor. */
        MODULE
    }
}
