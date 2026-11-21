// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import cc.jumpkick.host.CodeText;
import java.util.regex.Pattern;

/**
 * The source-shape patterns the shortener and the type index share. Blanking and the FQCN shape are
 * {@link CodeText}'s; positions in a blanked copy match the original source.
 */
final class JavaText {

    private JavaText() {}

    /** The FQCN shape, owned by {@link CodeText} so the shortener and the FQCN ratchet agree. */
    static final Pattern FQCN = CodeText.FQCN;

    /**
     * A dotted name as a declaration writes it. Java allows whitespace, so a line break or a blanked
     * comment, on either side of each dot; Kotlin allows a line break before one.
     */
    private static final String NAME = "\\w+(?:\\s*\\.\\s*\\w+)*";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * The trailing run is {@code [ \\t]*}, deliberately not {@code \\s*}: these patterns are matched
     * against the comment-blanked copy, where a javadoc block is a rectangle of spaces. A trailing
     * {@code \\s*$} then slides the match end past the blank lines AND the blanked comment to the next
     * line-end, so an import inserted at that offset landed after the class javadoc — which
     * palantir-java-format rejects outright as "Imports not contiguous". Ending the match at its own
     * line keeps the insertion point immediately after the last real import.
     */
    static final Pattern PACKAGE = Pattern.compile("(?m)^[ \\t]*package\\s+(" + NAME + ")[ \\t]*;?[ \\t]*$");

    /** A single-type import, possibly split across lines; group 2 is its name, whitespace and all. */
    static final Pattern IMPORT = Pattern.compile(
            "(?m)^[ \\t]*import\\s+(static\\s+)?(" + NAME + ")(?:[ \\t]+as[ \\t]+\\w+)?[ \\t]*;?[ \\t]*$");

    /** An upper-case-initial identifier: the shape a type name takes in all four languages. */
    static final Pattern TYPE_NAME = Pattern.compile("(?<![\\w.$])[A-Z][A-Za-z0-9_]*");

    /**
     * An import whose supplied simple names this pass cannot enumerate: on-demand ({@code x.*},
     * Scala {@code x._}), a Java module import ({@code import module java.base;}), a Scala/Kotlin
     * brace list, or an {@code as} alias. Any of these can already be binding the name a new
     * single-type import would claim.
     */
    static final Pattern OPAQUE_IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?" + NAME + "\\s*\\.\\s*(?:\\*|_\\s*$|\\{)"
                    + "|^\\s*import\\s+" + NAME + "\\s+as\\s+\\w+"
                    + "|^\\s*import\\s+module\\s+" + NAME + "\\s*;");

    static final Pattern TYPE_DECL = Pattern.compile(
            "\\b(?:class|interface|enum|record|object|trait)\\s+([A-Z][\\w]*)|@interface\\s+([A-Z][\\w]*)");

    /** Comments and literal bodies blanked to spaces; {@link CodeText} owns the lexer. */
    static String blanked(String src) {
        return CodeText.blank(src, CodeText.Blank.COMMENTS_AND_STRINGS);
    }

    static String packageName(String source) {
        var m = PACKAGE.matcher(source);
        return m.find() ? name(m.group(1)) : "";
    }

    /** A dotted name matched by {@link #PACKAGE} or {@link #IMPORT}, with its whitespace removed. */
    static String name(String matched) {
        return WHITESPACE.matcher(matched).replaceAll("");
    }

    static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    static boolean javaLang(String fqcn) {
        return fqcn.startsWith("java.lang.") && fqcn.indexOf('.', "java.lang.".length()) < 0;
    }
}
