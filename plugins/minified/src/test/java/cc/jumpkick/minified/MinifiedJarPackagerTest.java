// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.minified;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.BuildStamps;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**top-level `$` class names vs nested types, and the output jar's directory-entry contract. */
class MinifiedJarPackagerTest {

    @Test
    void nested_class_with_outer_peer_is_skipped() {
        Set<String> paths = Set.of("com/ex/Outer.class", "com/ex/Outer$Inner.class");
        assertThat(MinifiedJarPackager.isNestedClassFile("com/ex/Outer$Inner.class", paths))
                .isTrue();
        assertThat(MinifiedJarPackager.isNestedClassFile("com/ex/Outer.class", paths))
                .isFalse();
    }

    @Test
    void top_level_dollar_name_without_outer_is_kept() {
        // Legal JVM top-level simple name containing `$` — no Outer.class peer.
        Set<String> paths = Set.of("com/ex/Foo$Bar.class");
        assertThat(MinifiedJarPackager.isNestedClassFile("com/ex/Foo$Bar.class", paths))
                .isFalse();
    }

    @Test
    void classes_input_leaves_the_compile_freshness_stamps_behind(@TempDir Path tmp) throws Exception {
        // R8 copies an unrecognised input straight through, so a stamp taken into the program
        // input ships in the shrunk jar with a wall clock inside it.
        Path classes = Files.createDirectories(tmp.resolve("classes/com/ex"));
        Files.writeString(classes.resolve("A.class"), "class");
        for (String stamp : BuildStamps.ALL) {
            Files.writeString(tmp.resolve("classes").resolve(stamp), "STAMP_MILLIS 1758000000000");
        }

        Path input = tmp.resolve("classes.jar");
        MinifiedJarPackager.zipClasses(tmp.resolve("classes"), input);

        assertThat(entryNames(input)).containsExactly("com/ex/A.class");
    }

    @Test
    void output_jar_synthesizes_parent_directory_entries(@TempDir Path tmp) throws Exception {
        // R8's output carries no directory entries, but frameworks enumerate resource
        // directories via them (Micronaut SoftServiceLoader over META-INF/micronaut/...) —
        // the -min.jar owes the same contract as thin and fat jars.
        Path shrunk = tmp.resolve("shrunk.jar");
        try (OutputStream out = Files.newOutputStream(shrunk);
                JarOutputStream jos = new JarOutputStream(out)) {
            for (String name : List.of("com/ex/A.class", "META-INF/micronaut/io.acme.Marker/com.ex.Impl", "root.txt")) {
                jos.putNextEntry(new JarEntry(name));
                jos.write(new byte[] {1});
                jos.closeEntry();
            }
        }

        Path artifact = tmp.resolve("app-min.jar");
        MinifiedJarPackager.writeOutputJar(shrunk, artifact, "com.ex.A");

        List<String> names = entryNames(artifact);
        assertThat(names)
                .contains("META-INF/", "com/", "com/ex/", "META-INF/micronaut/", "META-INF/micronaut/io.acme.Marker/");
        // Dirs precede their files, each exactly once.
        assertThat(names.indexOf("META-INF/")).isLessThan(names.indexOf("META-INF/MANIFEST.MF"));
        assertThat(names.indexOf("com/ex/")).isLessThan(names.indexOf("com/ex/A.class"));
        assertThat(names.indexOf("META-INF/micronaut/io.acme.Marker/"))
                .isLessThan(names.indexOf("META-INF/micronaut/io.acme.Marker/com.ex.Impl"));
        assertThat(names).doesNotHaveDuplicates();

        // Determinism: a second rewrite is byte-identical.
        Path again = tmp.resolve("again-min.jar");
        MinifiedJarPackager.writeOutputJar(shrunk, again, "com.ex.A");
        assertThat(Files.readAllBytes(again)).isEqualTo(Files.readAllBytes(artifact));
    }

    private static List<String> entryNames(Path jar) throws Exception {
        List<String> names = new ArrayList<>();
        try (JarFile in = new JarFile(jar.toFile())) {
            for (Enumeration<JarEntry> e = in.entries(); e.hasMoreElements(); ) {
                names.add(e.nextElement().getName());
            }
        }
        return names;
    }

    @Test
    void appendModuleClassKeeps_includes_top_level_dollar_class(@TempDir Path tmp) throws Exception {
        Path classes = tmp.resolve("classes");
        Path pkg = classes.resolve("com/ex");
        Files.createDirectories(pkg);
        Files.write(pkg.resolve("Outer.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
        Files.write(pkg.resolve("Outer$Inner.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
        Files.write(pkg.resolve("Foo$Bar.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});

        StringBuilder pro = new StringBuilder();
        MinifiedJarPackager.appendModuleClassKeeps(pro, classes);
        String rules = pro.toString();
        assertThat(rules).contains("-keep class com.ex.Outer ");
        assertThat(rules).contains("-keep class com.ex.Foo$Bar ");
        assertThat(rules).doesNotContain("-keep class com.ex.Outer$Inner ");
    }
}
