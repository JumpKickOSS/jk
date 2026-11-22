// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static cc.jumpkick.graph.JavaText.importLine;
import static cc.jumpkick.graph.MoveMember.canonical;

import cc.jumpkick.graph.MoveMember.FileEdit;
import cc.jumpkick.graph.MoveMember.Op;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The import lines a move edits: static imports renamed, imports the edits left unused dropped, new ones added. */
final class MoveImports {

    private MoveImports() {}

    /** Rewrites {@code import static <from>.<name>;} to the target in {@code f}; the line's references may be any count. */
    static void statics(FileEdit f, String fromBin, String toBin, String name) {
        Matcher m = Pattern.compile("(?m)^\\s*import\\s+static\\s+(" + Pattern.quote(canonical(fromBin)) + ")\\.("
                        + Pattern.quote(name) + ")\\s*;")
                .matcher(f.text);
        while (m.find()) {
            f.ops.add(new Op(m.start(1), m.end(1), canonical(toBin)));
            f.free.add(m.start(1));
        }
    }

    /**
     * Drops each single-type import of an edited file whose every use the edits took away: the uses of
     * the source type that now name the target, and in the source's file the uses inside the code
     * that moved out. An import nothing used before the move stays as it was.
     */
    static void remove(Map<String, FileEdit> files) {
        for (FileEdit f : files.values()) {
            if (f.ops.isEmpty()) continue;
            Matcher m = Pattern.compile("(?m)^[ \\t]*import\\s+([\\w.]+)\\s*;[ \\t]*\\n")
                    .matcher(f.text);
            while (m.find()) {
                String imported = m.group(1);
                if (f.needs.contains(imported)) continue;
                boolean taken = false;
                boolean used = false;
                for (ReferenceIndex.Span s : f.spans) {
                    if (!s.symbol().startsWith("T ")
                            || !canonical(s.symbol().substring(2)).equals(imported)) continue;
                    if (importLine(f.text, s.start())) continue;
                    if (f.ops.stream()
                            .anyMatch(op -> op.start() < op.end() && op.start() <= s.start() && s.end() <= op.end())) {
                        taken = true;
                    } else {
                        used = true;
                    }
                }
                if (taken && !used) f.ops.add(new Op(m.start(), m.end(), ""));
            }
        }
    }

    /** The import lines {@code f} needs, inserted after its last import, else after its package line. */
    static Op op(FileEdit f) {
        StringBuilder lines = new StringBuilder();
        for (String i : f.imports) lines.append("import ").append(i).append(";\n");
        for (String i : f.staticImports)
            lines.append("import static ").append(i).append(";\n");
        Matcher m = MoveMember.IMPORT.matcher(f.text);
        int last = -1;
        while (m.find()) last = m.end();
        if (last >= 0) {
            int at = f.text.indexOf('\n', last);
            at = at < 0 ? f.text.length() : at + 1;
            return new Op(at, at, lines.toString());
        }
        Matcher p = MoveMember.PACKAGE.matcher(f.text);
        if (p.find()) {
            int at = f.text.indexOf('\n', p.end());
            at = at < 0 ? f.text.length() : at + 1;
            return new Op(at, at, "\n" + lines);
        }
        return new Op(0, 0, lines + "\n");
    }

    /** The {@code a.b.Owner.name} or {@code a.b.Owner.*} a source's static import names {@code member} by, or {@code null}. */
    static @Nullable String staticImport(String text, String owner, String member) {
        Matcher m = MoveMember.IMPORT.matcher(text);
        while (m.find()) {
            if (m.group(1) == null) continue;
            if (m.group(2).equals(owner + "." + member) || m.group(2).equals(owner + ".*")) return m.group(2);
        }
        return null;
    }
}
