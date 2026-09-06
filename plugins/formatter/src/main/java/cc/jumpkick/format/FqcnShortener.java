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
        // Everything structural is read off the BLANKED copy, never the raw text. Reading `package`
        // and `import` from raw source meant a commented-out import suppressed the import that was
        // actually needed, a `package` line inside a text block was taken for the file's own, and the
        // insertion point could land inside a block comment or a text block — rewriting a string
        // literal's contents. Offsets in the blanked copy match the original, so positions found here
        // are still valid against `source`.
        String blanked = JavaText.blanked(source);
        String pkg = JavaText.packageName(blanked);
        Map<String, String> imported = existingImports(blanked); // simple → fqcn

        record Hit(int start, int end, String fqcn, String simple) {}
        List<Hit> hits = new ArrayList<>();
        Matcher m = JavaText.FQCN.matcher(blanked);
        while (m.find()) {
            if (onImportOrPackageLine(blanked, m.start())) continue;
            String fqcn = m.group();
            hits.add(new Hit(m.start(), m.end(), fqcn, JavaText.simpleName(fqcn)));
        }
        if (hits.isEmpty()) return Result.unchanged(source);

        // An import this pass cannot read the members of — `x.*`, Scala `x._` or `x.{A, B}`, or a
        // Kotlin/Scala `as` alias. Such a form can already be supplying any simple name, so a NEW
        // single-type import may steal a name the file is relying on. Shortening to a name that is
        // already explicitly imported stays allowed; introducing one does not.
        boolean opaqueImports = hasOpaqueImport(blanked);
        int[] ranges = new int[hits.size() * 2];
        for (int i = 0; i < hits.size(); i++) {
            ranges[i * 2] = hits.get(i).start();
            ranges[i * 2 + 1] = hits.get(i).end();
        }
        Set<String> bare = bareTypeNames(blanked, ranges);

        // simple → the one FQCN we will bind, or null if colliding in this file
        Map<String, String> bind = new LinkedHashMap<>();
        Set<String> collided = new LinkedHashSet<>();
        for (Hit h : hits) {
            if (!index.contains(h.fqcn)) continue;
            if (!nameIsFree(h.simple, h.fqcn, pkg, imported, index, bare, opaqueImports)) {
                collided.add(h.simple);
                bind.remove(h.simple);
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
        // Re-blank `rewritten`, never reuse `blanked`: the rewrite shortened the text, so offsets from
        // the original no longer land in the same places. Only a blanked copy of the very string being
        // edited has matching offsets.
        if (!newImports.isEmpty()) rewritten = insertImports(rewritten, newImports, syntax);
        return new Result(rewritten, true);
    }

    /**
     * Whether {@code simple} can be bound to {@code fqcn} in this file without displacing something
     * already in scope.
     *
     * <p>The original guard consulted only the file's other qualified references and its explicit
     * single-type imports, which is the narrowest of the scopes that can own a simple name. It missed
     * the file's own declarations, its own package, {@code java.lang}, and on-demand imports. In every
     * one of those cases the shortened reference itself is fine — a new single-type import legally
     * shadows a package member — and the damage lands on the file's <em>pre-existing bare uses</em> of
     * that name, which silently rebind to a different type or stop compiling.
     *
     * <p>Hence the decisive test: if the simple name already appears as a bare type name anywhere else
     * in this file, the name is taken and the reference stays qualified. Two cases are exempt because
     * the bare occurrences already denote the very same type — the name is explicitly imported as this
     * FQCN, or this FQCN lives in the file's own package.
     */
    private static boolean nameIsFree(
            String simple,
            String fqcn,
            String pkg,
            Map<String, String> imported,
            TypeIndex index,
            Set<String> bare,
            boolean opaqueImports) {
        String already = imported.get(simple);
        if (already != null) return already.equals(fqcn); // explicit import decides, either way
        boolean ownPackage = !pkg.isEmpty() && fqcn.equals(pkg + "." + simple);
        if (ownPackage) return true; // already in scope unqualified, and it is the same type
        // From here we would be introducing a new binding for `simple`.
        if (bare.contains(simple)) return false;
        if (opaqueImports) return false;
        // A same-named type in this file's own package, or in java.lang, is in scope without an
        // import and is not this type.
        if (!pkg.isEmpty() && index.contains(pkg + "." + simple)) return false;
        return !index.contains("java.lang." + simple);
    }

    /** True when the file has an import whose supplied names this pass cannot enumerate. */
    private static boolean hasOpaqueImport(String blanked) {
        return JavaText.OPAQUE_IMPORT.matcher(blanked).find();
    }

    /**
     * Upper-case-initial identifiers appearing outside the qualified references we are about to
     * rewrite — the names already spoken for in this file, whatever declared them. {@code ranges} is
     * the flat {@code [start, end, start, end, …]} of those references, masked out so a qualified
     * reference's own trailing simple name does not count as a bare use of itself.
     */
    private static Set<String> bareTypeNames(String blanked, int[] ranges) {
        StringBuilder masked = new StringBuilder(blanked);
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            for (int j = ranges[i]; j < ranges[i + 1] && j < masked.length(); j++) {
                masked.setCharAt(j, ' ');
            }
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher m = JavaText.TYPE_NAME.matcher(masked);
        while (m.find()) out.add(m.group());
        return out;
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
        return insertImports(source, fqcns, Syntax.JAVA, JavaText.blanked(source));
    }

    static String insertImports(String source, Set<String> fqcns, Syntax syntax) {
        return insertImports(source, fqcns, syntax, JavaText.blanked(source));
    }

    /**
     * Insert an import block into {@code source}, choosing the position from {@code blanked} so the
     * anchor can never be an {@code import} or {@code package} line that only exists inside a comment
     * or a string. Offsets in the blanked copy are the same as in the original.
     */
    static String insertImports(String source, Set<String> fqcns, Syntax syntax, String blanked) {
        String semi = syntax.semicolonImports() ? ";" : "";
        List<String> lines = new ArrayList<>();
        for (String f : fqcns) lines.add("import " + f + semi);
        String block = String.join("\n", lines);
        Matcher imports = JavaText.IMPORT.matcher(blanked);
        int lastEnd = -1;
        while (imports.find()) lastEnd = imports.end();
        if (lastEnd >= 0) {
            return source.substring(0, lastEnd) + "\n" + block + source.substring(lastEnd);
        }
        Matcher pkg = JavaText.PACKAGE.matcher(blanked);
        if (pkg.find()) {
            int end = pkg.end();
            return source.substring(0, end) + "\n\n" + block + source.substring(end);
        }
        return block + "\n\n" + source;
    }
}
