// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The test compile's module options against a main classes tree that carries a descriptor. */
class ModularCompileTest {

    @Test
    void test_sources_without_a_descriptor_patch_the_main_module(@TempDir Path dir) throws IOException {
        Path classes = modularMain(dir);
        Path test = write(dir.resolve("test"), "com/acme/app/AppTest.java", "package com.acme.app; class AppTest {}");

        assertThat(ModularCompile.testOptions(List.of("-g"), classes, List.of(test)))
                .containsExactly(
                        "-g",
                        "--patch-module",
                        "com.acme.app=" + dir.resolve("test").toAbsolutePath().normalize(),
                        "--add-reads",
                        "com.acme.app=ALL-UNNAMED");
    }

    @Test
    void a_test_descriptor_for_the_main_module_has_the_main_classes_patched_in(@TempDir Path dir) throws IOException {
        Path classes = modularMain(dir);
        Path descriptor = write(dir.resolve("test"), "module-info.java", "module com.acme.app { }");
        Path test = write(dir.resolve("test"), "com/acme/app/AppTest.java", "package com.acme.app; class AppTest {}");

        assertThat(ModularCompile.testOptions(List.of(), classes, List.of(descriptor, test)))
                .containsExactly(
                        "--patch-module",
                        "com.acme.app=" + classes.toAbsolutePath(),
                        "--add-reads",
                        "com.acme.app=ALL-UNNAMED");
    }

    @Test
    void a_main_tree_without_a_descriptor_adds_nothing(@TempDir Path dir) throws IOException {
        Path test = write(dir.resolve("test"), "a/ATest.java", "package a; class ATest {}");

        assertThat(ModularCompile.testOptions(List.of("-g"), dir.resolve("classes"), List.of(test)))
                .containsExactly("-g");
    }

    private static Path modularMain(Path dir) throws IOException {
        Path src = dir.resolve("main");
        Path descriptor = write(src, "module-info.java", "module com.acme.app { exports com.acme.app; }");
        Path app = write(src, "com/acme/app/App.java", "package com.acme.app; public class App {}");
        Path out = dir.resolve("classes");
        Files.createDirectories(out);
        int rc = ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", out.toString(), descriptor.toString(), app.toString());
        assertThat(rc).isZero();
        return out;
    }

    private static Path write(Path root, String rel, String body) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
        return f;
    }
}
