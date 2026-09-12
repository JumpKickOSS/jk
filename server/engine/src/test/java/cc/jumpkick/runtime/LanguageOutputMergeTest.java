// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.task.FreshnessStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The classes tree a jar is packed from is shared by javac's output, the sibling languages' merged
 * output and the resources. A class that stops existing in any of those must leave the tree, or
 * the jar ships it beside its replacement.
 */
class LanguageOutputMergeTest {

    @Test
    void a_class_the_language_compiler_dropped_leaves_the_merged_tree(@TempDir Path tmp) throws IOException {
        Path ktOut = tmp.resolve("target/classes/kotlin");
        Path classes = tmp.resolve("target/classes/main");
        Path buildDir = tmp.resolve("target");
        write(ktOut.resolve("com/x/Foo.class"), "foo");
        write(ktOut.resolve("com/x/FooKt.class"), "fookt");
        // javac's own output shares the tree and is nobody else's to remove.
        write(classes.resolve("com/x/Baz.class"), "baz");

        PlannerSupport.mergeLanguageOutput(ktOut, classes, buildDir, "kotlin");
        assertThat(classes.resolve("com/x/Foo.class")).exists();
        assertThat(classes.resolve("com/x/FooKt.class")).exists();

        // Foo.kt now declares Bar: kotlinc's incremental compiler prunes Foo.class from its dir.
        Files.delete(ktOut.resolve("com/x/Foo.class"));
        write(ktOut.resolve("com/x/Bar.class"), "bar");
        PlannerSupport.mergeLanguageOutput(ktOut, classes, buildDir, "kotlin");

        assertThat(classes.resolve("com/x/Bar.class")).exists();
        assertThat(classes.resolve("com/x/FooKt.class")).exists();
        assertThat(classes.resolve("com/x/Foo.class"))
                .as("the renamed-away class is not packaged beside its replacement")
                .doesNotExist();
        assertThat(classes.resolve("com/x/Baz.class"))
                .as("Java's class is not the mirror's to remove")
                .exists();
    }

    @Test
    void each_language_keeps_its_own_ledger(@TempDir Path tmp) throws IOException {
        Path ktOut = tmp.resolve("target/classes/kotlin");
        Path gvOut = tmp.resolve("target/classes/groovy");
        Path classes = tmp.resolve("target/classes/main");
        Path buildDir = tmp.resolve("target");
        write(ktOut.resolve("K.class"), "k");
        write(gvOut.resolve("G.class"), "g");

        PlannerSupport.mergeLanguageOutput(ktOut, classes, buildDir, "kotlin");
        PlannerSupport.mergeLanguageOutput(gvOut, classes, buildDir, "groovy");
        // A Groovy merge with an emptied Groovy tree removes G, and only G.
        Files.delete(gvOut.resolve("G.class"));
        PlannerSupport.mergeLanguageOutput(gvOut, classes, buildDir, "groovy");

        assertThat(classes.resolve("G.class")).doesNotExist();
        assertThat(classes.resolve("K.class")).exists();
    }

    @Test
    void an_emptied_java_source_set_takes_its_stale_classes_with_it(@TempDir Path tmp) throws IOException {
        Path javaOut = tmp.resolve("target/classes/main");
        Path src = write(tmp.resolve("src/main/java/A.java"), "class A {}");
        write(javaOut.resolve("A.class"), "stale");
        FreshnessStamp.write(javaOut, BuildStamps.JAVA, "compile-main", "", List.of(src), List.of(), 25, "");

        // Every .java deleted, the root left behind: the compile has no sources and never runs.
        Files.delete(src);
        PlannerCompile.dropOutputOfRemovedSources(javaOut);

        assertThat(javaOut).isDirectory();
        assertThat(javaOut.resolve("A.class")).doesNotExist();
        assertThat(javaOut.resolve(BuildStamps.JAVA))
                .as("the stamp goes with the classes it described")
                .doesNotExist();
    }

    @Test
    void a_tree_that_never_had_java_sources_is_left_alone(@TempDir Path tmp) throws IOException {
        Path javaOut = tmp.resolve("target/classes/main");
        // Kotlin-only output that compile-kotlin published straight into the tree.
        write(javaOut.resolve("K.class"), "k");

        PlannerCompile.dropOutputOfRemovedSources(javaOut);
        assertThat(javaOut.resolve("K.class")).exists();

        // A stamp that recorded no sources describes no classes to drop either.
        FreshnessStamp.write(javaOut, BuildStamps.JAVA, "compile-main", "", List.of(), List.of(), 25, "");
        PlannerCompile.dropOutputOfRemovedSources(javaOut);
        assertThat(javaOut.resolve("K.class")).exists();
    }

    private static Path write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, body);
    }
}
