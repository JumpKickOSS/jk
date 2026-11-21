// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.minified;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.BuildStamps;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Library keep rules, the R8 program input, and the output jar's entries and manifest. */
class MinifiedJarPackagerTest {

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
        MinifiedJarPackager.writeOutputJar(shrunk, artifact, "com.ex.A", Map.of());

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
        MinifiedJarPackager.writeOutputJar(shrunk, again, "com.ex.A", Map.of());
        assertThat(Files.readAllBytes(again)).isEqualTo(Files.readAllBytes(artifact));
    }

    @Test
    void output_jar_carries_the_manifest_table_in_declaration_order(@TempDir Path tmp) throws Exception {
        Path shrunk = tmp.resolve("shrunk.jar");
        try (OutputStream out = Files.newOutputStream(shrunk);
                JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry("com/ex/A.class"));
            jos.write(new byte[] {1});
            jos.closeEntry();
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Implementation-Version", "1.2.3");
        attributes.put("Automatic-Module-Name", "com.ex");

        Path artifact = tmp.resolve("lib-min.jar");
        MinifiedJarPackager.writeOutputJar(shrunk, artifact, null, attributes);

        try (JarFile jar = new JarFile(artifact.toFile())) {
            String manifest = new String(
                    jar.getInputStream(jar.getEntry(JarFile.MANIFEST_NAME)).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(manifest)
                    .isEqualTo("Manifest-Version: 1.0\r\n"
                            + "Implementation-Version: 1.2.3\r\n"
                            + "Automatic-Module-Name: com.ex\r\n"
                            + "\r\n");
        }
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
    void appendModuleClassKeeps_keeps_nested_and_top_level_dollar_classes(@TempDir Path tmp) throws Exception {
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
        // A keep on Outer covers its members only; a library's callers use Outer.Inner directly.
        assertThat(rules).contains("-keep class com.ex.Outer$Inner ");
    }
}
