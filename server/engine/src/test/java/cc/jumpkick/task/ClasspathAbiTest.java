// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ClasspathAbiTest {

    @Test
    void jar_and_classes_dir_of_the_same_classes_share_a_token(@TempDir Path dir) throws Exception {
        byte[] cls = classWithReturn(1);
        Path classes = dir.resolve("classes");
        writeClass(classes, "C.class", cls);
        Path jar = jar(dir.resolve("c.jar"), "C.class", cls);
        withCache(dir.resolve("cache"), () -> {
            try {
                String a = ClasspathAbi.token(jar);
                String b = ClasspathAbi.token(classes);
                assertThat(a).startsWith("abi:");
                assertThat(a).isEqualTo(b);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void body_only_and_private_and_resource_keep_the_token(@TempDir Path dir) throws Exception {
        Path jar1 = jar(dir.resolve("a.jar"), "C.class", classWithReturn(1));
        Path jar2 = jar(dir.resolve("b.jar"), "C.class", classWithReturn(2));
        Path jarPriv = jar(dir.resolve("p.jar"), "C.class", classWithPrivate("x"));
        Path jarPriv2 = jar(dir.resolve("p2.jar"), "C.class", classWithPrivate("y"));
        Path jarRes = jarWithResource(dir.resolve("r.jar"), "C.class", classWithReturn(1), "META-INF/x", "one");
        Path jarRes2 = jarWithResource(dir.resolve("r2.jar"), "C.class", classWithReturn(1), "META-INF/x", "two");
        withCache(dir.resolve("cache"), () -> {
            try {
                assertThat(ClasspathAbi.token(jar1)).isEqualTo(ClasspathAbi.token(jar2));
                assertThat(ClasspathAbi.token(jarPriv)).isEqualTo(ClasspathAbi.token(jarPriv2));
                assertThat(ClasspathAbi.token(jarRes)).isEqualTo(ClasspathAbi.token(jarRes2));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void public_method_and_constant_change_the_token(@TempDir Path dir) throws Exception {
        Path a = jar(dir.resolve("a.jar"), "C.class", classWithMethods("n"));
        Path b = jar(dir.resolve("b.jar"), "C.class", classWithMethods("n", "m"));
        Path c1 = jar(dir.resolve("c1.jar"), "C.class", classWithIntConst(1));
        Path c2 = jar(dir.resolve("c2.jar"), "C.class", classWithIntConst(2));
        withCache(dir.resolve("cache"), () -> {
            try {
                assertThat(ClasspathAbi.token(a)).isNotEqualTo(ClasspathAbi.token(b));
                assertThat(ClasspathAbi.token(c1)).isNotEqualTo(ClasspathAbi.token(c2));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void missing_is_distinct_from_abi(@TempDir Path dir) throws Exception {
        Path jar = jar(dir.resolve("c.jar"), "C.class", classWithReturn(1));
        Path gone = dir.resolve("nope.jar");
        withCache(dir.resolve("cache"), () -> {
            try {
                String abi = ClasspathAbi.token(jar);
                String missing = ClasspathAbi.token(gone);
                assertThat(abi).startsWith("abi:");
                assertThat(missing).startsWith("missing:");
                assertThat(missing).isNotEqualTo(abi);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void same_bytes_at_two_paths_share_a_token_and_the_second_does_not_extract(@TempDir Path dir) throws Exception {
        byte[] cls = classWithReturn(1);
        Path jar = jar(dir.resolve("a.jar"), "C.class", cls);
        Path copy = dir.resolve("b.jar");
        Files.copy(jar, copy);
        withCache(dir.resolve("cache"), () -> {
            try {
                ClasspathAbi.resetStats();
                AbiMemo.resetStats();
                String a = ClasspathAbi.token(jar);
                assertThat(ClasspathAbi.extracts()).isEqualTo(1);
                String b = ClasspathAbi.token(copy);
                assertThat(b).isEqualTo(a);
                assertThat(ClasspathAbi.extracts())
                        .as("second path must reuse the content-keyed extract")
                        .isEqualTo(1);
                assertThat(AbiMemo.hits()).isGreaterThanOrEqualTo(1);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void resource_only_dir_keeps_the_token(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes");
        writeClass(classes, "C.class", classWithReturn(1));
        Files.writeString(classes.resolve("res.txt"), "one");
        withCache(dir.resolve("cache"), () -> {
            try {
                String before = ClasspathAbi.token(classes);
                Files.writeString(classes.resolve("res.txt"), "two");
                assertThat(ClasspathAbi.token(classes)).isEqualTo(before);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void withCache(Path cache, Runnable body) {
        SessionContext.runWhere(Session.defaults().withCacheDir(cache), () -> {
            AbiMemo.reset();
            AbiMemo.resetStats();
            ClasspathAbi.resetStats();
            FileHashMemo.reset();
            FileHashMemo.resetStats();
            body.run();
        });
    }

    private static Path jar(Path file, String name, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry(name));
            out.write(bytes);
            out.closeEntry();
        }
        return file;
    }

    private static Path jarWithResource(Path file, String className, byte[] cls, String resName, String res)
            throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry(className));
            out.write(cls);
            out.closeEntry();
            out.putNextEntry(new ZipEntry(resName));
            out.write(res.getBytes());
            out.closeEntry();
        }
        return file;
    }

    private static void writeClass(Path root, String rel, byte[] bytes) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
    }

    private static byte[] classWithReturn(int v) {
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

    private static byte[] classWithPrivate(String name) {
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

    private static byte[] classWithIntConst(int v) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
        FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "X", "I", null, v);
        fv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
