// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A descriptor compiled for a newer JDK than the one jk runs on reads all the same: the engine on
 * its own JDK reads a Java 27 project's module-info for the module path, javadoc and module services.
 */
class ModuleDescriptorsTest {

    @Test
    void a_descriptor_from_a_newer_class_file_version_reads_its_name_requires_and_provides(@TempDir Path dir)
            throws Exception {
        byte[] current = compiledDescriptor(dir);
        byte[] future = current.clone();
        int major = Runtime.version().feature() + 44 + 2;
        future[6] = (byte) (major >>> 8);
        future[7] = (byte) major;
        assertThatThrownBy(() -> ModuleDescriptor.read(ByteBuffer.wrap(future)))
                .as("the JDK's own reader refuses it, which is the point")
                .isInstanceOf(InvalidModuleDescriptorException.class);

        ModuleDescriptor d = ModuleDescriptors.read(future);

        assertThat(d.name()).isEqualTo("ex.svc");
        assertThat(d.requires()).anyMatch(r -> r.name().equals("java.logging"));
        assertThat(d.provides()).singleElement().satisfies(p -> {
            assertThat(p.service()).isEqualTo("java.lang.Runnable");
            assertThat(p.providers()).containsExactly("ex.svc.Impl");
        });
    }

    @Test
    void entries_are_explicit_from_a_tree_or_jar_automatic_from_a_plain_jar_and_none_from_a_plain_tree(
            @TempDir Path dir) throws Exception {
        compiledDescriptor(dir);
        Path classes = dir.resolve("classes");
        Path explicitJar = jar(dir.resolve("svc-1.0.jar"), classes, "module-info.class");
        Path plainJar = jar(dir.resolve("plain-lib-2.0.jar"), classes, "ex/svc/Impl.class");
        Path plainTree = dir.resolve("plain");
        Files.createDirectories(plainTree.resolve("ex"));

        assertThat(ModuleDescriptors.of(classes))
                .hasValueSatisfying(d -> assertThat(d.name()).isEqualTo("ex.svc"));
        assertThat(ModuleDescriptors.of(explicitJar))
                .hasValueSatisfying(d -> assertThat(d.isAutomatic()).isFalse());
        assertThat(ModuleDescriptors.of(plainJar)).hasValueSatisfying(d -> {
            assertThat(d.isAutomatic()).isTrue();
            assertThat(d.name()).isEqualTo("plain.lib");
        });
        assertThat(ModuleDescriptors.of(plainTree)).isEmpty();
    }

    private static byte[] compiledDescriptor(Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/ex/svc"));
        Files.writeString(
                src.resolve("Impl.java"),
                "package ex.svc; public class Impl implements Runnable { public void run() {} }");
        Path info = Files.writeString(
                dir.resolve("src/module-info.java"),
                "module ex.svc { requires java.logging; provides java.lang.Runnable with ex.svc.Impl; }");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        int rc = ToolProvider.getSystemJavaCompiler()
                .run(
                        null,
                        null,
                        null,
                        "-d",
                        classes.toString(),
                        info.toString(),
                        src.resolve("Impl.java").toString());
        assertThat(rc).isZero();
        return Files.readAllBytes(classes.resolve("module-info.class"));
    }

    private static Path jar(Path jar, Path classes, String entry) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(entry));
            out.write(Files.readAllBytes(classes.resolve(entry)));
            out.closeEntry();
        }
        return jar;
    }
}
