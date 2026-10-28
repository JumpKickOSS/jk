// SPDX-License-Identifier: Apache-2.0
//
// The ground truth for bench/graph-retrieval: every compiled class under the given directories, its
// supertypes, its methods with their line spans, and every call each method makes, read from the
// class files with the JDK's own java.lang.classfile API. It shares no code with jk graph, so the
// bench grades jk and the other tools against the same independent answer.
//
//   java bench/graph-retrieval/Truth.java <classes-dir>... > truth.jsonl
//
// One JSON object per line: {"class", "source", "super", "interfaces", "interface", "synthetic",
// "outer", "methods": [{"name", "desc", "first", "last", "synthetic", "abstract", "calls":
// [{"owner", "name", "desc", "line"}]}]}. Lines are source lines from LineNumberTable; a method
// without one has first = last = 0.

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class Truth {

    public static void main(String[] args) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String dir : args) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) continue;
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(p -> p.toString().endsWith(".class"))
                        .sorted()
                        .toList();
            }
            for (Path file : files) {
                ClassModel cm = ClassFile.of().parse(Files.readAllBytes(file));
                classJson(out, cm, root.toString());
                out.append('\n');
            }
        }
        System.out.print(out);
    }

    private static void classJson(StringBuilder out, ClassModel cm, String root) {
        out.append("{\"root\":").append(str(root));
        out.append(",\"class\":").append(str(cm.thisClass().asInternalName()));
        String source = cm.findAttribute(Attributes.sourceFile())
                .map(a -> a.sourceFile().stringValue())
                .orElse(null);
        out.append(",\"source\":").append(source == null ? "null" : str(source));
        out.append(",\"super\":")
                .append(cm.superclass().map(c -> str(c.asInternalName())).orElse("null"));
        out.append(",\"interfaces\":[");
        for (int i = 0; i < cm.interfaces().size(); i++) {
            if (i > 0) out.append(',');
            out.append(str(cm.interfaces().get(i).asInternalName()));
        }
        out.append(']');
        out.append(",\"interface\":").append(cm.flags().has(AccessFlag.INTERFACE));
        out.append(",\"synthetic\":").append(cm.flags().has(AccessFlag.SYNTHETIC));
        String outer = cm.findAttribute(Attributes.enclosingMethod())
                .map(a -> a.enclosingClass().asInternalName()
                        + a.enclosingMethodName()
                                .map(n -> "#" + n.stringValue())
                                .orElse(""))
                .orElse(null);
        out.append(",\"outer\":").append(outer == null ? "null" : str(outer));
        out.append(",\"methods\":[");
        boolean firstMethod = true;
        for (MethodModel m : cm.methods()) {
            if (!firstMethod) out.append(',');
            firstMethod = false;
            methodJson(out, m);
        }
        out.append("]}");
    }

    private static void methodJson(StringBuilder out, MethodModel m) {
        int first = Integer.MAX_VALUE;
        int last = 0;
        int line = 0;
        List<String> calls = new ArrayList<>();
        var code = m.code();
        if (code.isPresent()) {
            for (CodeElement e : code.get()) {
                if (e instanceof LineNumber ln) {
                    line = ln.line();
                    first = Math.min(first, line);
                    last = Math.max(last, line);
                } else if (e instanceof InvokeInstruction inv) {
                    calls.add("{\"owner\":" + str(inv.owner().asInternalName())
                            + ",\"name\":" + str(inv.name().stringValue())
                            + ",\"desc\":" + str(inv.type().stringValue())
                            + ",\"line\":" + line + "}");
                }
            }
        }
        if (first == Integer.MAX_VALUE) first = 0;
        out.append("{\"name\":").append(str(m.methodName().stringValue()));
        out.append(",\"desc\":").append(str(m.methodType().stringValue()));
        out.append(",\"first\":").append(first).append(",\"last\":").append(last);
        out.append(",\"synthetic\":")
                .append(m.flags().has(AccessFlag.SYNTHETIC) || m.flags().has(AccessFlag.BRIDGE));
        out.append(",\"abstract\":").append(m.flags().has(AccessFlag.ABSTRACT));
        out.append(",\"calls\":[").append(String.join(",", calls)).append("]}");
    }

    private static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
