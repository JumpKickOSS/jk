// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class AssemblyPackagerTest {

    @Test
    void merges_classes_deps_services_and_drops_signatures(@TempDir Path tmp) throws IOException {
        // Project classes dir.
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("app"));
        Files.writeString(classes.resolve("app/Main.class"), "APPMAIN");
        Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(classes.resolve("META-INF/services/com.example.SPI"), "app.Provider");

        // A dependency jar with a class, a service file, and a (bogus) signature.
        Path dep = tmp.resolve("dep.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(dep))) {
            putEntry(jos, "lib/Helper.class", "LIBHELPER");
            putEntry(jos, "META-INF/services/com.example.SPI", "lib.Provider");
            putEntry(jos, "META-INF/FOO.SF", "signature");
            putEntry(jos, "META-INF/FOO.RSA", "signature");
        }

        Path out = tmp.resolve("app-all.jar");
        new AssemblyPackager()
                .packageAssembly(new AssemblyPackager.AssemblyRequest(
                        classes, List.of(dep), out, "app.Main", Map.of("Implementation-Title", "app"), 0L));

        try (JarFile jf = new JarFile(out.toFile())) {
            Manifest mf = jf.getManifest();
            assertThat(mf.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS))
                    .isEqualTo("app.Main");
            assertThat(mf.getMainAttributes().getValue("Implementation-Title")).isEqualTo("app");

            assertThat(jf.getJarEntry("app/Main.class")).isNotNull();
            assertThat(jf.getJarEntry("lib/Helper.class")).isNotNull();
            // Signature files are dropped.
            assertThat(jf.getJarEntry("META-INF/FOO.SF")).isNull();
            assertThat(jf.getJarEntry("META-INF/FOO.RSA")).isNull();

            // Service file merges both providers.
            String svc = new String(
                    jf.getInputStream(jf.getJarEntry("META-INF/services/com.example.SPI"))
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(svc).contains("app.Provider").contains("lib.Provider");
        }
    }

    @Test
    void drops_per_dependency_maven_metadata_but_keeps_licence_and_notice_files(@TempDir Path tmp) throws IOException {
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("app"));
        Files.writeString(classes.resolve("app/Main.class"), "APPMAIN");

        Path dep = tmp.resolve("dep.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(dep))) {
            putEntry(jos, "lib/Helper.class", "LIBHELPER");
            putEntry(jos, "META-INF/maven/com.example/lib/pom.xml", "<project/>");
            putEntry(jos, "META-INF/maven/com.example/lib/pom.properties", "version=1.0");
            putEntry(jos, "META-INF/LICENSE.txt", "Apache-2.0");
            putEntry(jos, "META-INF/NOTICE", "Copyright");
            putEntry(jos, "META-INF/licenses/dep-LICENSE", "MIT");
        }

        Path out = tmp.resolve("app-all.jar");
        new AssemblyPackager()
                .packageAssembly(
                        new AssemblyPackager.AssemblyRequest(classes, List.of(dep), out, "app.Main", Map.of(), 0L));

        try (JarFile jf = new JarFile(out.toFile())) {
            assertThat(jf.getJarEntry("META-INF/maven/com.example/lib/pom.xml")).isNull();
            assertThat(jf.getJarEntry("META-INF/maven/com.example/lib/pom.properties"))
                    .isNull();
            assertThat(jf.getEntry("META-INF/maven/")).isNull();
            assertThat(jf.getJarEntry("META-INF/LICENSE.txt")).isNotNull();
            assertThat(jf.getJarEntry("META-INF/NOTICE")).isNotNull();
            assertThat(jf.getJarEntry("META-INF/licenses/dep-LICENSE")).isNotNull();
            assertThat(jf.getJarEntry("lib/Helper.class")).isNotNull();
        }
    }

    @Test
    void merges_spring_meta_inf_and_excludes_module_info(@TempDir Path tmp) throws IOException {
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("META-INF"));
        Files.createDirectories(classes.resolve("app"));
        Files.writeString(classes.resolve("META-INF/spring.handlers"), "http://app=app.Ns");
        Files.writeString(classes.resolve("app/Main.class"), "APP");

        Path dep = tmp.resolve("dep.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(dep))) {
            putEntry(jos, "META-INF/spring.handlers", "http://lib=lib.Ns");
            putEntry(jos, "module-info.class", "MODULE");
            putEntry(jos, "lib/Helper.class", "HELP");
        }

        Path out = tmp.resolve("fat.jar");
        new AssemblyPackager()
                .packageAssembly(
                        new AssemblyPackager.AssemblyRequest(classes, List.of(dep), out, "app.Main", Map.of(), 0L));

        try (JarFile jf = new JarFile(out.toFile())) {
            assertThat(jf.getJarEntry("module-info.class")).isNull();
            assertThat(jf.getJarEntry("lib/Helper.class")).isNotNull();
            String handlers = new String(
                    jf.getInputStream(jf.getJarEntry("META-INF/spring.handlers"))
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(handlers).contains("http://app=app.Ns").contains("http://lib=lib.Ns");
        }
    }

    @Test
    void project_class_wins_on_conflict(@TempDir Path tmp) throws IOException {
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("x"));
        Files.writeString(classes.resolve("x/A.class"), "PROJECT");
        Path dep = tmp.resolve("dep.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(dep))) {
            putEntry(jos, "x/A.class", "DEP");
        }
        Path out = tmp.resolve("out.jar");
        new AssemblyPackager()
                .packageAssembly(new AssemblyPackager.AssemblyRequest(classes, List.of(dep), out, null, Map.of(), 0L));

        try (JarFile jf = new JarFile(out.toFile())) {
            String content =
                    new String(jf.getInputStream(jf.getJarEntry("x/A.class")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).isEqualTo("PROJECT");
        }
    }

    @Test
    void reproducible_and_excludes_freshness_stamps(@TempDir Path tmp) throws IOException {
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("app"));
        Files.writeString(classes.resolve("app/Main.class"), "APPMAIN");
        Files.writeString(classes.resolve(".jstamp"), "stamp-run-1");
        Path dep = tmp.resolve("dep.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(dep))) {
            putEntry(jos, "lib/Helper.class", "LIBHELPER");
        }

        Path a = tmp.resolve("a-all.jar");
        new AssemblyPackager()
                .packageAssembly(
                        new AssemblyPackager.AssemblyRequest(classes, List.of(dep), a, "app.Main", Map.of(), 0L));
        // The freshness stamp's content changes every build; the fat jar must
        // not bundle it (and the manifest must be pinned), or the jar churns.
        Files.writeString(classes.resolve(".jstamp"), "stamp-run-2-different");
        Path b = tmp.resolve("b-all.jar");
        new AssemblyPackager()
                .packageAssembly(
                        new AssemblyPackager.AssemblyRequest(classes, List.of(dep), b, "app.Main", Map.of(), 0L));

        assertThat(Files.readAllBytes(a)).isEqualTo(Files.readAllBytes(b));
        try (JarFile jf = new JarFile(a.toFile())) {
            assertThat(jf.getJarEntry(".jstamp")).as("freshness stamp excluded").isNull();
            assertThat(jf.getJarEntry("META-INF/MANIFEST.MF").getTime())
                    .as("manifest pinned to the same fixed epoch as data entries")
                    .isEqualTo(jf.getJarEntry("app/Main.class").getTime());
        }
    }

    @Test
    void manifest_attributes_are_written_in_name_order_whatever_order_the_map_iterates(@TempDir Path tmp)
            throws IOException {
        // Same contract as the thin jar: the attribute order is part of the bytes, and the request's
        // immutable copy iterates in a per-JVM order, so the packager orders by name.
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("app"));
        Files.writeString(classes.resolve("app/Main.class"), "APPMAIN");
        Map<String, String> scrambled = new LinkedHashMap<>();
        scrambled.put("Sbom-Location", "META-INF/sbom/application.cdx.json");
        scrambled.put("Implementation-Version", "1.0.0");
        scrambled.put("Sbom-Format", "CycloneDX");
        scrambled.put("Implementation-Title", "widget");

        Path jar = tmp.resolve("app-all.jar");
        new AssemblyPackager()
                .packageAssembly(
                        new AssemblyPackager.AssemblyRequest(classes, List.of(), jar, "app.Main", scrambled, 0L));

        try (JarFile jf = new JarFile(jar.toFile());
                InputStream in = jf.getInputStream(jf.getJarEntry("META-INF/MANIFEST.MF"))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("Manifest-Version: 1.0\r\n"
                            + "Main-Class: app.Main\r\n"
                            + "Implementation-Title: widget\r\n"
                            + "Implementation-Version: 1.0.0\r\n"
                            + "Sbom-Format: CycloneDX\r\n"
                            + "Sbom-Location: META-INF/sbom/application.cdx.json\r\n"
                            + "\r\n");
        }
    }

    @Test
    void relocates_packages_across_class_bytes_entry_paths_and_service_files(@TempDir Path tmp) throws IOException {
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("app"));
        // app.Main holds an IndexReader field and names the class as a string, as Class.forName would.
        Files.write(
                classes.resolve("app/Main.class"), referencingClass("app/Main", "org/apache/lucene/index/IndexReader"));

        Path dep = tmp.resolve("lucene.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(dep))) {
            jos.putNextEntry(new JarEntry("org/apache/lucene/index/IndexReader.class"));
            jos.write(referencingClass("org/apache/lucene/index/IndexReader", "org/apache/lucene/store/Directory"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("org/apache/lucene/store/Directory.class"));
            jos.write(referencingClass("org/apache/lucene/store/Directory", "java/lang/Object"));
            jos.closeEntry();
            putEntry(jos, "org/apache/lucene/util/version.properties", "lucene=9");
            putEntry(jos, "META-INF/services/org.apache.lucene.codecs.Codec", "org.apache.lucene.codecs.Lucene99Codec");
            putEntry(jos, "org/other/Keep.txt", "untouched");
        }

        Path out = tmp.resolve("app-all.jar");
        new AssemblyPackager()
                .packageAssembly(new AssemblyPackager.AssemblyRequest(
                        classes,
                        List.of(dep),
                        out,
                        "app.Main",
                        Map.of(),
                        Map.of(),
                        Map.of("org.apache.lucene", "org.demo.shaded.lucene9"),
                        0L));

        try (JarFile jf = new JarFile(out.toFile())) {
            assertThat(jf.getJarEntry("org/demo/shaded/lucene9/index/IndexReader.class"))
                    .isNotNull();
            assertThat(jf.getJarEntry("org/apache/lucene/index/IndexReader.class"))
                    .isNull();
            assertThat(jf.getJarEntry("org/demo/shaded/lucene9/util/version.properties"))
                    .isNotNull();
            assertThat(jf.getJarEntry("org/other/Keep.txt")).isNotNull();
            // the moved class refers to its moved neighbour
            ClassReader reader =
                    new ClassReader(jf.getInputStream(jf.getJarEntry("org/demo/shaded/lucene9/index/IndexReader.class"))
                            .readAllBytes());
            assertThat(reader.getClassName()).isEqualTo("org/demo/shaded/lucene9/index/IndexReader");
            assertThat(reader.getSuperName()).isEqualTo("org/demo/shaded/lucene9/store/Directory");
            // the project's own class follows the move: its field type and its string constant
            byte[] main = jf.getInputStream(jf.getJarEntry("app/Main.class")).readAllBytes();
            String text = new String(main, StandardCharsets.ISO_8859_1);
            assertThat(text).contains("org/demo/shaded/lucene9/index/IndexReader");
            assertThat(text).contains("org.demo.shaded.lucene9.index.IndexReader");
            assertThat(text).doesNotContain("org/apache/lucene");
            // the service file moves with its provider
            assertThat(jf.getJarEntry("META-INF/services/org.apache.lucene.codecs.Codec"))
                    .isNull();
            String svc = new String(
                    jf.getInputStream(jf.getJarEntry("META-INF/services/org.demo.shaded.lucene9.codecs.Codec"))
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(svc.trim()).isEqualTo("org.demo.shaded.lucene9.codecs.Lucene99Codec");
        }
    }

    /** {@code public class <name> extends <ref> { <ref> field; static final String NAME = "<ref dotted>"; }}. */
    private static byte[] referencingClass(String name, String ref) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, name, null, ref, null);
        cw.visitField(Opcodes.ACC_PUBLIC, "field", "L" + ref + ";", null, null).visitEnd();
        cw.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        "NAME",
                        "Ljava/lang/String;",
                        null,
                        ref.replace('/', '.'))
                .visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void putEntry(JarOutputStream jos, String name, String content) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(content.getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();
    }

    @Test
    void directory_entries_for_soft_service_loader(@TempDir Path tmp) throws Exception {
        // Micronaut SoftServiceLoader enumerates META-INF/micronaut/... as directories.
        Path classes = tmp.resolve("classes");
        Path micronaut = classes.resolve("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference");
        Files.createDirectories(micronaut);
        Files.writeString(micronaut.resolve("com.example.$Foo$Definition"), "ref");
        Path out = tmp.resolve("app-all.jar");
        new AssemblyPackager()
                .packageAssembly(
                        new AssemblyPackager.AssemblyRequest(classes, List.of(), out, "app.Main", Map.of(), 0L));
        try (JarFile jf = new JarFile(out.toFile())) {
            assertThat(jf.getEntry("META-INF/")).isNotNull();
            assertThat(jf.getEntry("META-INF/micronaut/")).isNotNull();
            assertThat(jf.getEntry("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/"))
                    .isNotNull();
            assertThat(
                            jf.getEntry(
                                    "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/com.example.$Foo$Definition"))
                    .isNotNull();
        }
    }
}
