// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.Hashing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Language-neutral ABI fingerprint of a JVM class file. API includes {@code ConstantValue} so
 * inlined {@code public static final} constants classify as ABI, matching Zinc. Named nested
 * classes fold into their owner's fingerprint ({@link #of(byte[], SortedMap)}), so an API change
 * inside {@code Foo.Builder} flips {@code Foo} to ABI (JK-2616). A body hash used to ride along;
 * {@link #classify} never read it, so it is gone — half the hashing for the same answers.
 */
public final class ClassAbi {

    private ClassAbi() {}

    public record Fingerprint(String apiHex) {}

    public static Fingerprint of(byte[] classBytes) {
        return new Fingerprint(Hashing.sha256Hex(String.join("\n", apiLines(classBytes))));
    }

    /**
     * Fingerprint of an owner class plus its named nested classes' API surfaces, keyed by nested
     * class file name (sorted, so order is stable). Anonymous/local classes ({@code Foo$1}) are the
     * caller's to exclude — a body edit that adds one must stay BODY.
     */
    public static Fingerprint of(byte[] ownerBytes, SortedMap<String, byte[]> nestedByName) {
        List<String> lines = new ArrayList<>(apiLines(ownerBytes));
        for (var e : nestedByName.entrySet()) {
            lines.add("$ " + e.getKey());
            lines.addAll(apiLines(e.getValue()));
        }
        return new Fingerprint(Hashing.sha256Hex(String.join("\n", lines)));
    }

    private static List<String> apiLines(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ApiCollector api = new ApiCollector();
        cr.accept(api, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return api.lines;
    }

    public enum Kind {
        ABI,
        BODY
    }

    /**
     * Compare a dirty FQC's current bytes to the pre-compile fingerprint. Matching hashes still
     * yield {@link Kind#BODY} so a git-dirty source name-matches.
     */
    public static Kind classify(Fingerprint previous, Fingerprint current) {
        if (previous == null) return Kind.ABI;
        if (!previous.apiHex().equals(current.apiHex())) return Kind.ABI;
        return Kind.BODY;
    }

    private static final class ApiCollector extends ClassVisitor {
        final List<String> lines = new ArrayList<>();

        ApiCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            lines.add("C " + access + " " + name + " " + nullToEmpty(superName));
            if (interfaces != null) {
                List<String> ifs = new ArrayList<>(List.of(interfaces));
                Collections.sort(ifs);
                for (String i : ifs) lines.add("I " + i);
            }
        }

        @Override
        public void visitNestHost(String nestHost) {
            lines.add("N " + nestHost);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (visible) lines.add("A " + descriptor);
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (isPrivate(access)) return null;
            lines.add("F " + access + " " + name + " " + descriptor);
            if (value != null) lines.add("V " + name + "=" + value);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            if (isPrivate(access)) return null;
            lines.add("M " + access + " " + name + " " + descriptor);
            return null;
        }

        private static boolean isPrivate(int access) {
            return (access & Opcodes.ACC_PRIVATE) != 0;
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }
    }
}
