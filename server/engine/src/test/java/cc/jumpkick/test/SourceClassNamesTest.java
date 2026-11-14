// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The classes a test source declares, wherever it sits under its root. */
class SourceClassNamesTest {

    @Test
    void a_kotlin_file_names_its_top_level_classes_in_its_package_beside_its_path(@TempDir Path root) throws Exception {
        Path kt = Files.writeString(root.resolve("OrderSpecs.kt"), """
                package com.acme

                // class NotAClass
                val text = "class AlsoNot {"

                class FirstIT {
                    companion object Inner {}
                    @org.junit.jupiter.api.Test fun ok() {}
                }

                data class SecondIT(val x: Int)

                object Helpers
                """);
        assertThat(SourceClassNames.of(root, kt))
                .containsExactly("OrderSpecs", "com.acme.FirstIT", "com.acme.SecondIT", "com.acme.Helpers");
    }

    @Test
    void a_java_file_names_its_path_and_every_top_level_class(@TempDir Path root) throws Exception {
        Path java = root.resolve("com/acme/CartTest.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, """
                package com.acme;
                class CartTest { class Inner {} Object o = new Object() {}; Class<?> c = String.class; }
                class CartFixture {}
                """);
        assertThat(SourceClassNames.of(root, java)).containsExactly("com.acme.CartTest", "com.acme.CartFixture");
    }
}
