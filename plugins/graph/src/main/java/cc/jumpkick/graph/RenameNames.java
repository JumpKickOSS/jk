// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** What a new name may be, and a descriptor's parameters as an answer names them. */
final class RenameNames {

    private RenameNames() {}

    static final Set<String> KEYWORDS = Set.of(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null",
            "var",
            // Not record: Java restricts it only as a type name, and it is no Kotlin keyword. A
            // method may be called record. yield stays: an unqualified call of it does not compile.
            "yield",
            "fun",
            "val",
            "when",
            "object",
            "is",
            "in",
            "as",
            "typealias");

    /** {@code (Ljava/lang/String;[I)V} → {@code [String, int[]]}. */
    static List<String> paramNames(String desc) {
        List<String> out = new ArrayList<>();
        int i = 1;
        while (i < desc.length() && desc.charAt(i) != ')') {
            int dims = 0;
            while (desc.charAt(i) == '[') {
                dims++;
                i++;
            }
            String t;
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                t = simple(desc.substring(i + 1, end));
                i = end + 1;
            } else {
                t = switch (c) {
                    case 'Z' -> "boolean";
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'S' -> "short";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'F' -> "float";
                    case 'D' -> "double";
                    default -> String.valueOf(c);
                };
                i++;
            }
            out.add(t + "[]".repeat(dims));
        }
        return out;
    }

    static boolean identifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0)) || KEYWORDS.contains(name)) return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
    }

    private static String simple(String internal) {
        String s = internal.substring(internal.lastIndexOf('/') + 1);
        return s.substring(s.lastIndexOf('$') + 1);
    }
}
