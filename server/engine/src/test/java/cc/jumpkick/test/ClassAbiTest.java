// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.ClassAbi;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;

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

    @Test
    void private_member_change_is_body() {
        assertThat(ClassAbi.of(classWithPrivateField("a")).apiHex())
                .isEqualTo(ClassAbi.of(classWithPrivateField("b")).apiHex());
    }

    @Test
    void public_method_add_is_abi() {
        assertThat(ClassAbi.classify(ClassAbi.of(classWithMethods("n")), ClassAbi.of(classWithMethods("n", "m"))))
                .isEqualTo(ClassAbi.Kind.ABI);
    }

    @Test
    void signature_only_change_is_abi() {
        assertThat(ClassAbi.classify(
                        ClassAbi.of(classWithSignature(null)),
                        ClassAbi.of(classWithSignature("Ljava/lang/Comparable<TE;>;"))))
                .isEqualTo(ClassAbi.Kind.ABI);
    }

    @Test
    void throws_only_change_is_abi() {
        assertThat(ClassAbi.classify(
                        ClassAbi.of(classWithThrows()), ClassAbi.of(classWithThrows("java/io/IOException"))))
                .isEqualTo(ClassAbi.Kind.ABI);
    }

    @Test
    void record_component_change_is_abi() {
        assertThat(ClassAbi.classify(ClassAbi.of(recordClass("x", "I")), ClassAbi.of(recordClass("y", "I"))))
                .isEqualTo(ClassAbi.Kind.ABI);
    }

    @Test
    void permits_change_is_abi() {
        assertThat(ClassAbi.classify(ClassAbi.of(sealedClass("A")), ClassAbi.of(sealedClass("B"))))
                .isEqualTo(ClassAbi.Kind.ABI);
    }

    @Test
    void member_reorder_is_stable() {
        assertThat(ClassAbi.of(classWithFields("a", "b")).apiHex())
                .isEqualTo(ClassAbi.of(classWithFields("b", "a")).apiHex());
    }

    @Test
    void kotlin_metadata_is_ignored() {
        assertThat(ClassAbi.of(classWithKotlinMetadata(1)).apiHex())
                .isEqualTo(ClassAbi.of(classWithKotlinMetadata(2)).apiHex());
    }

    @Test
    void annotation_value_change_is_abi() {
        assertThat(ClassAbi.classify(ClassAbi.of(classWithAnn(1)), ClassAbi.of(classWithAnn(2))))
                .isEqualTo(ClassAbi.Kind.ABI);
    }

    @Test
    void module_info_export_change_is_abi() {
        assertThat(ClassAbi.classify(ClassAbi.of(moduleInfo("a")), ClassAbi.of(moduleInfo("b"))))
                .isEqualTo(ClassAbi.Kind.ABI);
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

    private static byte[] classWithPrivateField(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        FieldVisitor fv = cw.visitField(Opcodes.ACC_PRIVATE, name, "I", null, null);
        fv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWithMethods(String... names) {
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

    private static byte[] classWithSignature(String signature) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", signature, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWithThrows(String... exceptions) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        String[] ex = exceptions.length == 0 ? null : exceptions;
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "n", "()V", null, ex);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] recordClass(String component, String desc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_RECORD, "R", null, "java/lang/Record", null);
        RecordComponentVisitor rv = cw.visitRecordComponent(component, desc, null);
        rv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] sealedClass(String permitted) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "S", null, "java/lang/Object", null);
        cw.visitPermittedSubclass(permitted);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWithFields(String... names) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        for (String n : names) {
            FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC, n, "I", null, null);
            fv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWithKotlinMetadata(int k) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation("Lkotlin/Metadata;", true);
        av.visit("k", k);
        av.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWithAnn(int x) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation("LAnn;", true);
        av.visit("x", x);
        av.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] moduleInfo(String exported) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_MODULE, "module-info", null, null, null);
        var mv = cw.visitModule("m", 0, null);
        mv.visitExport(exported, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
