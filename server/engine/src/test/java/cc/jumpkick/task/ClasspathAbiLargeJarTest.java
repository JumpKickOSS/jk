// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * The ABI token of a jar is read within a heap far smaller than the jar's directory: a bundle of a
 * quarter million classes is keyed in a 64 MiB JVM, where holding its directory and one entry
 * object per class would need more than that.
 */
@Tag("integration")
class ClasspathAbiLargeJarTest {

    private static final int CLASSES = 250_000;

    @Test
    void a_quarter_million_class_jar_is_keyed_inside_a_64_mib_heap(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("bundle.jar");
        writeBundle(jar);
        Path log = dir.resolve("fork.log");
        List<String> cmd = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m",
                "-XX:+UseSerialGC",
                "-cp",
                System.getProperty("java.class.path"),
                ClasspathAbiLargeJarTest.class.getName(),
                jar.toString(),
                dir.resolve("cache").toString());
        Process fork = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean done = fork.waitFor(5, TimeUnit.MINUTES);
        if (!done) fork.destroyForcibly();
        String out = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(done).as("fork finished: " + out).isTrue();
        assertThat(fork.exitValue()).as(out).isZero();
        assertThat(out.strip()).startsWith("abi:");
    }

    /** The forked side: one token of {@code args[0]} under a cache at {@code args[1]}, printed. */
    public static void main(String[] args) {
        SessionContext.runWhere(Session.defaults().withCacheDir(Path.of(args[1])), () -> {
            try {
                System.out.println(ClasspathAbi.token(Path.of(args[0])));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** {@link #CLASSES} copies of one small class under long package names, stored so the write is quick. */
    private static void writeBundle(Path jar) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "com/example/bundle/C", null, "java/lang/Object", null);
        cw.visitEnd();
        byte[] cls = cw.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(cls);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < CLASSES; i++) {
                ZipEntry e = new ZipEntry("com/example/bundle/generated/services/region" + (i % 97)
                        + "/model/transform/Class" + i + ".class");
                e.setMethod(ZipEntry.STORED);
                e.setSize(cls.length);
                e.setCompressedSize(cls.length);
                e.setCrc(crc.getValue());
                out.putNextEntry(e);
                out.write(cls);
                out.closeEntry();
            }
        }
    }
}
