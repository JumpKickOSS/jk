// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.FactsExtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** The environment reads a class file makes, read from its bytecode. */
public class EnvReadsTest {

    /**
     * A class whose {@code named()} reads {@code name} by a constant, {@code computed(String)} by its
     * argument, {@code all()} the whole map, and whose {@code conditioned()} carries JUnit's
     * {@code @EnabledIfEnvironmentVariable(named = condition)}.
     */
    public static byte[] reader(String internalName, String name, String condition) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor named = cw.visitMethod(Opcodes.ACC_STATIC, "named", "()Ljava/lang/String;", null, null);
        named.visitCode();
        named.visitLdcInsn(name);
        named.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/System", "getenv", "(Ljava/lang/String;)Ljava/lang/String;", false);
        named.visitInsn(Opcodes.ARETURN);
        named.visitMaxs(0, 0);
        named.visitEnd();
        MethodVisitor computed =
                cw.visitMethod(Opcodes.ACC_STATIC, "computed", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        computed.visitCode();
        computed.visitVarInsn(Opcodes.ALOAD, 0);
        computed.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/System", "getenv", "(Ljava/lang/String;)Ljava/lang/String;", false);
        computed.visitInsn(Opcodes.ARETURN);
        computed.visitMaxs(0, 0);
        computed.visitEnd();
        MethodVisitor all = cw.visitMethod(Opcodes.ACC_STATIC, "all", "()Ljava/util/Map;", null, null);
        all.visitCode();
        all.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv", "()Ljava/util/Map;", false);
        all.visitInsn(Opcodes.ARETURN);
        all.visitMaxs(0, 0);
        all.visitEnd();
        MethodVisitor conditioned = cw.visitMethod(Opcodes.ACC_PUBLIC, "conditioned", "()V", null, null);
        AnnotationVisitor a =
                conditioned.visitAnnotation("Lorg/junit/jupiter/api/condition/EnabledIfEnvironmentVariable;", true);
        a.visit("named", condition);
        a.visit("matches", ".*");
        a.visitEnd();
        conditioned.visitCode();
        conditioned.visitInsn(Opcodes.RETURN);
        conditioned.visitMaxs(0, 0);
        conditioned.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void constant_computed_whole_map_and_condition_reads_are_told_apart() {
        List<EnvReads.Site> sites =
                EnvReads.ofClass(FactsExtractor.extract(reader("ex/Reader", "MY_TOKEN_VALUE", "RUN_SLOW")));

        assertThat(sites)
                .containsExactlyInAnyOrder(
                        new EnvReads.Site(EnvReads.Kind.NAMED, "ex.Reader", "MY_TOKEN_VALUE"),
                        new EnvReads.Site(EnvReads.Kind.COMPUTED, "ex.Reader", ""),
                        new EnvReads.Site(EnvReads.Kind.WHOLE_MAP, "ex.Reader", ""),
                        new EnvReads.Site(EnvReads.Kind.NAMED, "ex.Reader", "RUN_SLOW"));
    }

    @Test
    void a_jar_is_read_once_per_content_and_its_answer_kept(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("dep.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("dep/Client.class"));
            out.write(reader("dep/Client", "DEP_API_KEY", "DEP_FLAG"));
            out.closeEntry();
        }
        Path cache = tmp.resolve("cache");

        assertThat(EnvReads.ofJar(jar, cache))
                .contains(new EnvReads.Site(EnvReads.Kind.NAMED, "dep.Client", "DEP_API_KEY"));
        Path kept;
        try (var files = Files.list(cache)) {
            kept = files.toList().getFirst();
        }
        Files.writeString(kept, "NAMED\tdep.Client\tFROM_THE_CACHE\n");
        assertThat(EnvReads.ofJar(jar, cache))
                .as("the same content is answered from the cache")
                .containsExactly(new EnvReads.Site(EnvReads.Kind.NAMED, "dep.Client", "FROM_THE_CACHE"));
    }
}
