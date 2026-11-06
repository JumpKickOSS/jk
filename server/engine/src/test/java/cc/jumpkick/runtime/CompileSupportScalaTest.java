// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.base.CompileSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompileSupportScalaTest {

    @Test
    void collect_scala_simple_layout(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Hello.scala"), "object Hello");
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        assertThat(CompileSupport.collectScalaSources(tmp, true))
                .extracting(p -> p.getFileName().toString())
                .containsExactly("Hello.scala");
    }

    @Test
    void collect_scala_traditional_layout(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/scala/com/acme"));
        Files.writeString(tmp.resolve("src/main/scala/com/acme/Hello.scala"), "object Hello");
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("src/main/java/Stray.scala"), "object Stray");
        assertThat(CompileSupport.collectScalaSources(tmp, false))
                .extracting(p -> p.getFileName().toString())
                .containsExactlyInAnyOrder("Hello.scala", "Stray.scala");
    }

    @Test
    void java_under_the_scala_root_is_collected_traditional(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/scala/p"));
        Files.writeString(tmp.resolve("src/main/scala/p/package-info.java"), "package p;");
        Files.writeString(tmp.resolve("src/main/scala/p/Hello.scala"), "package p\nobject Hello");
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("src/main/java/Plain.java"), "class Plain {}");
        assertThat(CompileSupport.collectJavaInScalaRoot(tmp, false))
                .extracting(p -> p.getFileName().toString())
                .containsExactly("package-info.java");
    }

    @Test
    void java_in_scala_root_is_empty_for_simple_layout(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        assertThat(CompileSupport.collectJavaInScalaRoot(tmp, true)).isEmpty();
    }
}
