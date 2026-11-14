// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The top-level classes a test source compiles to, read before it compiles. A Java file's public
 * class is named by its path, but a Kotlin, Groovy or Scala file may sit anywhere under its root
 * and declare any number of classes in any package ({@code src/integration/kotlin/OrderSpecs.kt}
 * holding {@code package com.acme; class FirstIT}), so its path names no class. The answer is the
 * path's name and, for those languages and for a Java file's other top-level classes, every type
 * declared outside any brace in the file's package. A name too many is harmless to its readers; a
 * name missing leaves a suite's class to another suite.
 */
public final class SourceClassNames {

    private SourceClassNames() {}

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern TYPE = Pattern.compile(
            "(?<![\\w.$@])(?:class|interface|enum|record|object|trait)\\s+(?!(?:class|interface|object)\\b)([A-Za-z_$][\\w$]*)");

    /**
     * The binary names of the top-level classes {@code source}, a file under source root {@code
     * root}, declares: its path's name first. Only the path's name when the file cannot be read.
     */
    public static Set<String> of(Path root, Path source) {
        String rel = root.relativize(source).toString().replace('\\', '/');
        int dot = rel.lastIndexOf('.');
        Set<String> out = new LinkedHashSet<>();
        out.add((dot < 0 ? rel : rel.substring(0, dot)).replace('/', '.'));
        String text;
        try {
            text = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return out;
        }
        out.addAll(declared(text));
        return out;
    }

    /** The binary names of the types {@code text} declares outside any brace, in its package. */
    static List<String> declared(String text) {
        String code = masked(text);
        Matcher pkg = PACKAGE.matcher(code);
        String prefix = pkg.find() ? pkg.group(1) + "." : "";
        int[] depth = depths(code);
        List<String> out = new ArrayList<>();
        Matcher type = TYPE.matcher(code);
        while (type.find()) {
            if (depth[type.start()] == 0) out.add(prefix + type.group(1));
        }
        return out;
    }

    /** The brace depth at each offset of {@code code}. */
    private static int[] depths(String code) {
        int[] out = new int[code.length() + 1];
        int depth = 0;
        for (int i = 0; i < code.length(); i++) {
            out[i] = depth;
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && depth > 0) depth--;
        }
        out[code.length()] = depth;
        return out;
    }

    /** {@code text} with its comments, string and character literals blanked to spaces, offsets kept. */
    private static String masked(String text) {
        StringBuilder out = new StringBuilder(text);
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            int end;
            if (text.startsWith("//", i)) {
                end = text.indexOf('\n', i);
                end = end < 0 ? n : end;
            } else if (text.startsWith("/*", i)) {
                end = text.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
            } else if (text.startsWith("\"\"\"", i) || text.startsWith("'''", i)) {
                end = text.indexOf(text.substring(i, i + 3), i + 3);
                end = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                end = i + 1;
                while (end < n && text.charAt(end) != c && text.charAt(end) != '\n') {
                    end += text.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(n, end + 1);
            } else {
                i++;
                continue;
            }
            for (int k = i; k < end; k++) {
                if (out.charAt(k) != '\n') out.setCharAt(k, ' ');
            }
            i = end;
        }
        return out.toString();
    }
}
