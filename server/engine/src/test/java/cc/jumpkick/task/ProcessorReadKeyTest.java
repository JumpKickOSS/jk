// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.GroovycRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * What a compile's own code reads of a dependency beyond its JVM ABI moves the compile's key: the
 * {@code kotlin.Metadata} an annotation processor reads, and the body of a local Groovy AST
 * transform groovyc runs. Neither moves the key of a compile that reads neither.
 */
class ProcessorReadKeyTest {

    @Test
    void kotlin_metadata_moves_the_key_of_a_compile_that_runs_processors(@TempDir Path dir) throws IOException {
        Path src = Files.writeString(dir.resolve("Hello.java"), "class Hello {}");
        Path processor = AbiJars.jar(dir.resolve("processor.jar"), AbiJars.classReturning(1));
        Path dep = AbiJars.jar(dir.resolve("v1/dep.jar"), kotlinClass("Ljava/util/List<Ljava/lang/String;>;"));
        Path edited = AbiJars.jar(dir.resolve("v2/dep.jar"), kotlinClass("Ljava/util/List<Ljava/lang/String?>;"));
        assertThat(ClasspathAbi.token(edited))
                .as("the JVM signatures are the same")
                .isEqualTo(ClasspathAbi.token(dep));

        assertThat(ActionKey.javacClasspathTokens(javac(src, dir, dep, List.of(processor))))
                .as("a processor reads kotlin.Metadata")
                .isNotEqualTo(ActionKey.javacClasspathTokens(javac(src, dir, edited, List.of(processor))));
        assertThat(ActionKey.javacClasspathTokens(javac(src, dir, dep, List.of())))
                .as("javac alone does not")
                .isEqualTo(ActionKey.javacClasspathTokens(javac(src, dir, edited, List.of())));
    }

    @Test
    void a_local_transforms_body_moves_the_groovyc_key(@TempDir Path dir) throws IOException {
        Path v1 =
                jar(dir.resolve("v1/transform.jar"), Map.of("t/Marker.class", marker(false), "t/Impl.class", impl(1)));
        Path v2 =
                jar(dir.resolve("v2/transform.jar"), Map.of("t/Marker.class", marker(false), "t/Impl.class", impl(2)));
        assertThat(ClasspathAbi.token(v2)).as("a body edit").isEqualTo(ClasspathAbi.token(v1));

        assertThat(groovycTokens(dir, v1)).isNotEqualTo(groovycTokens(dir, v2));
    }

    @Test
    void a_local_transform_named_by_class_in_another_entry_keys_that_entry_by_content(@TempDir Path dir)
            throws IOException {
        Path annotation = jar(dir.resolve("annotation.jar"), Map.of("t/Marker.class", marker(true)));
        Path v1 = jar(dir.resolve("v1/impl.jar"), Map.of("t/Impl.class", impl(1)));
        Path v2 = jar(dir.resolve("v2/impl.jar"), Map.of("t/Impl.class", impl(2)));

        assertThat(groovycTokens(dir, annotation, v1)).isNotEqualTo(groovycTokens(dir, annotation, v2));
    }

    @Test
    void a_body_edit_with_no_transform_keeps_the_groovyc_key(@TempDir Path dir) throws IOException {
        Path v1 = jar(dir.resolve("v1/lib.jar"), Map.of("t/Impl.class", impl(1)));
        Path v2 = jar(dir.resolve("v2/lib.jar"), Map.of("t/Impl.class", impl(2)));

        assertThat(groovycTokens(dir, v1)).isEqualTo(groovycTokens(dir, v2));
    }

    private static CompileRequest javac(Path src, Path dir, Path dep, List<Path> processorPath) {
        return CompileRequest.builder()
                .sources(List.of(src))
                .classpath(List.of(dep))
                .processorPath(processorPath)
                .outputDir(dir.resolve("out"))
                .release(25)
                .build();
    }

    private static List<String> groovycTokens(Path dir, Path... classpath) throws IOException {
        GroovycRequest request = GroovycRequest.builder()
                .sources(List.of(dir.resolve("src/Script.groovy")))
                .classpath(List.of(classpath))
                .outputDir(dir.resolve("target/classes"))
                .stubsOut(dir.resolve("target/stubs"))
                .jvmTarget(21)
                .workerClasspath(List.of(dir.resolve("worker.jar")))
                .build();
        return ActionKey.groovycClasspathTokens(request);
    }

    /**
     * {@code public class C} with a {@code List<String>} field, and kotlinc's metadata giving the field
     * {@code kotlinType}: a type argument's nullability is in the metadata and not in the signature.
     */
    private static byte[] kotlinClass(String kotlinType) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        AnnotationVisitor metadata = cw.visitAnnotation("Lkotlin/Metadata;", true);
        metadata.visit("k", 1);
        AnnotationVisitor d2 = metadata.visitArray("d2");
        d2.visit(null, "names");
        d2.visit(null, kotlinType);
        d2.visitEnd();
        metadata.visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "names", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/String;>;", null)
                .visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code @interface t.Marker}, meta-annotated to run {@code t.Impl}, named by string or by class. */
    private static byte[] marker(boolean byClass) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V25,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "t/Marker",
                null,
                "java/lang/Object",
                new String[] {"java/lang/annotation/Annotation"});
        AnnotationVisitor av = cw.visitAnnotation("Lorg/codehaus/groovy/transform/GroovyASTTransformationClass;", true);
        AnnotationVisitor names = av.visitArray(byClass ? "classes" : "value");
        names.visit(null, byClass ? Type.getObjectType("t/Impl") : "t.Impl");
        names.visitEnd();
        av.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public class t.Impl { public int n() { return v; } }}: {@code v} moves the body only. */
    private static byte[] impl(int v) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "t/Impl", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "n", "()I", null, null);
        mv.visitCode();
        mv.visitLdcInsn(v);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Path jar(Path file, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> e : new TreeMap<>(entries).entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return file;
    }
}
