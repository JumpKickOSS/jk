// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Replace unique fully-qualified type names with a simple name plus an import. Comments and
 * string literals are left alone. Colliding simple names stay qualified.
 */
final class FqcnShortener {

    private FqcnShortener() {}

    record Result(String source, boolean changed) {
        static Result unchanged(String source) {
            return new Result(source, false);
        }
    }

    enum Syntax {
        JAVA,
        KOTLIN,
        GROOVY,
        SCALA;

        /** Java imports carry a semicolon; Kotlin/Groovy/Scala do not. */
        boolean semicolonImports() {
            return this == JAVA;
        }
    }

    static Result shorten(String source, TypeIndex index) {
        return shorten(source, index, Syntax.JAVA);
    }

    static Result shorten(String source, TypeIndex index, Syntax syntax) {
        if (source == null || source.isEmpty() || index == null) return Result.unchanged(source);
        String pkg = JavaText.packageName(source);
        Map<String, String> imported = existingImports(source); // simple → fqcn
        String blanked = JavaText.blankNonCode(source);

        record Hit(int start, int end, String fqcn, String simple) {}
        List<Hit> hits = new ArrayList<>();
        Matcher m = JavaText.FQCN.matcher(blanked);
        while (m.find()) {
            if (onImportOrPackageLine(blanked, m.start())) continue;
            String fqcn = m.group();
            hits.add(new Hit(m.start(), m.end(), fqcn, JavaText.simpleName(fqcn)));
        }
        if (hits.isEmpty()) return Result.unchanged(source);

        // simple → the one FQCN we will bind, or null if colliding in this file
        Map<String, String> bind = new LinkedHashMap<>();
        Set<String> collided = new LinkedHashSet<>();
        for (Hit h : hits) {
            if (!index.contains(h.fqcn)) continue;
            String already = imported.get(h.simple);
            if (already != null && !already.equals(h.fqcn)) {
                collided.add(h.simple);
                continue;
            }
            String bound = bind.get(h.simple);
            if (bound != null && !bound.equals(h.fqcn)) {
                collided.add(h.simple);
                bind.remove(h.simple);
                continue;
            }
            if (!collided.contains(h.simple)) bind.put(h.simple, h.fqcn);
        }

        Set<String> newImports = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder(source);
        boolean changed = false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (collided.contains(h.simple)) continue;
            String target = bind.get(h.simple);
            if (target == null || !target.equals(h.fqcn)) continue;
            if (!index.contains(h.fqcn)) continue;
            out.replace(h.start, h.end, h.simple);
            changed = true;
            if (needsImport(h.fqcn, h.simple, pkg, imported)) newImports.add(h.fqcn);
        }
        if (!changed) return Result.unchanged(source);
        String rewritten = out.toString();
        if (!newImports.isEmpty()) rewritten = insertImports(rewritten, newImports, syntax);
        return new Result(rewritten, true);
    }

    private static boolean needsImport(String fqcn, String simple, String pkg, Map<String, String> imported) {
        if (JavaText.javaLang(fqcn)) return false;
        if (!pkg.isEmpty() && fqcn.equals(pkg + "." + simple)) return false;
        String have = imported.get(simple);
        return have == null;
    }

    private static boolean onImportOrPackageLine(String blanked, int pos) {
        int lineStart = blanked.lastIndexOf('\n', pos - 1) + 1;
        int i = lineStart;
        while (i < blanked.length() && (blanked.charAt(i) == ' ' || blanked.charAt(i) == '\t')) i++;
        return blanked.startsWith("import ", i) || blanked.startsWith("package ", i);
    }

    private static Map<String, String> existingImports(String source) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = JavaText.IMPORT.matcher(source);
        while (m.find()) {
            if (m.group(1) != null) continue; // static
            String fqcn = m.group(2);
            if (fqcn.endsWith(".*")) continue;
            out.putIfAbsent(JavaText.simpleName(fqcn), fqcn);
        }
        return out;
    }

    static String insertImports(String source, Set<String> fqcns) {
        return insertImports(source, fqcns, Syntax.JAVA);
    }

    static String insertImports(String source, Set<String> fqcns, Syntax syntax) {
        String semi = syntax.semicolonImports() ? ";" : "";
        List<String> lines = new ArrayList<>();
        for (String f : fqcns) lines.add("import " + f + semi);
        String block = String.join("\n", lines);
        Matcher imports = JavaText.IMPORT.matcher(source);
        int lastEnd = -1;
        while (imports.find()) lastEnd = imports.end();
        if (lastEnd >= 0) {
            return source.substring(0, lastEnd) + "\n" + block + source.substring(lastEnd);
        }
        Matcher pkg = JavaText.PACKAGE.matcher(source);
        if (pkg.find()) {
            int end = pkg.end();
            return source.substring(0, end) + "\n\n" + block + source.substring(end);
        }
        return block + "\n\n" + source;
    }
}
