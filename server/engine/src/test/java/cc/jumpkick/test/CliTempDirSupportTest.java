// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code -Djunit.jupiter.tempdir.factory.default} names a class that lives in jk-cli's test tree,
 * so only a module that can load it may be told to use it. Naming it anyway is not a harmless
 * no-op: JUnit logs a stack trace per {@code @TempDir} and falls back to the default deletion
 * strategy, which is the soft-fail delete the flag exists to install.
 *
 * <p>The gate is whether the factory class is on the test classpath, not whether the module
 * declares a {@code [test] env}.
 */
class CliTempDirSupportTest {

    private static final String FACTORY = CliTempDirSupport.FACTORY_RESOURCE;

    @TempDir
    Path tmp;

    @Test
    void a_classes_dir_holding_the_factory_carries_support() throws Exception {
        Path classes = tmp.resolve("classes/test");
        Files.createDirectories(classes.resolve(FACTORY).getParent());
        Files.writeString(classes.resolve(FACTORY), "");

        assertThat(CliTempDirSupport.onClasspath(List.of(classes), FACTORY)).isTrue();
    }

    @Test
    void a_jar_holding_the_factory_carries_support() throws Exception {
        Path jar = tmp.resolve("jk-cli-tests.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry(FACTORY));
            jos.closeEntry();
        }

        assertThat(CliTempDirSupport.onClasspath(List.of(jar), FACTORY)).isTrue();
    }

    @Test
    void a_classpath_without_it_does_not() throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("engine/classes/test"));
        Files.writeString(classes.resolve("Whatever.class"), "");
        Path notAJar = Files.writeString(tmp.resolve("notes.txt"), "not a jar");
        Path missing = tmp.resolve("gone");

        assertThat(CliTempDirSupport.onClasspath(List.of(classes, notAJar, missing), FACTORY))
                .as("an unreadable or absent entry simply does not carry the classes")
                .isFalse();
    }
}
