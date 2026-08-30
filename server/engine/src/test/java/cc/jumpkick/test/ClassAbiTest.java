// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ClassAbiTest {

    @Test
    void constant_value_change_is_abi() {
        byte[] v1 = classWithIntConst(1);
        byte[] v2 = classWithIntConst(2);
        assertThat(ClassAbi.classify(ClassAbi.of(v1), ClassAbi.of(v2))).isEqualTo(ClassAbi.Kind.ABI);
    }

    @Test
    void method_body_change_is_body() {
        byte[] v1 = classWithReturn(1);
        byte[] v2 = classWithReturn(2);
        ClassAbi.Fingerprint a = ClassAbi.of(v1);
        ClassAbi.Fingerprint b = ClassAbi.of(v2);
        assertThat(a.apiHex()).isEqualTo(b.apiHex());
        assertThat(ClassAbi.classify(a, b)).isEqualTo(ClassAbi.Kind.BODY);
    }

    @Test
    void missing_previous_is_abi() {
        assertThat(ClassAbi.classify(null, ClassAbi.of(classWithReturn(1)))).isEqualTo(ClassAbi.Kind.ABI);
    }

    private static byte[] classWithIntConst(int v) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "X", "I", null, v);
        fv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWithReturn(int v) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "n", "()I", null, null);
        mv.visitCode();
        mv.visitLdcInsn(v);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
