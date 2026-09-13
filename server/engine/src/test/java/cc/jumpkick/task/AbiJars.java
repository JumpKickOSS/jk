// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Class files and jars with a chosen ABI, for tests that separate "same API, different bytes"
 * from "different API": one public class {@code C}, varied in its method bodies, its public
 * methods or its inlined constant.
 */
public final class AbiJars {

    private AbiJars() {}

    /** A jar holding {@code C.class} with the given bytes. */
    public static Path jar(Path file, byte[] classBytes) throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("C.class"));
            out.write(classBytes);
            out.closeEntry();
        }
        return file;
    }

    /** {@code public class C { public int n() { return v; } }} — the body is the only thing {@code v} moves. */
    public static byte[] classReturning(int v) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "n", "()I", null, null);
        mv.visitCode();
        mv.visitLdcInsn(v);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public class C} with one public no-op method per name. */
    public static byte[] classWithMethods(String... names) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        for (String n : names) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, n, "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public class C { public static final int X = v; }} — javac inlines {@code X} at every use. */
    public static byte[] classWithIntConst(int v) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "X", "I", null, v);
        fv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
