// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.PackagingKeys;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarPackagerTest {

    @Test
    void writes_sorted_entries(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input.resolve("z/sub"));
        Files.createDirectories(input.resolve("a"));
        Files.writeString(input.resolve("z/sub/Z.class"), "z");
        Files.writeString(input.resolve("a/A.class"), "a");
        Files.writeString(input.resolve("Root.class"), "r");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jar));

        List<String> entries = listEntries(jar);
        // META-INF/* entries come first (jar plumbing), then our files alphabetized
        // (directory entries interleave right before their first file).
        List<String> ours = entries.stream()
                .filter(e -> !e.startsWith("META-INF/") && !e.endsWith("/"))
                .toList();
        assertThat(ours).containsExactly("Root.class", "a/A.class", "z/sub/Z.class");
    }

    @Test
    void writes_directory_entries_for_resource_dir_scans(@TempDir Path tempDir) throws IOException {
        // Micronaut's SoftServiceLoader resolves META-INF/micronaut/<service>/ as a directory
        // resource; a jar with file entries only makes that lookup come back empty.
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input.resolve("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference"));
        Files.writeString(
                input.resolve("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/com.example.$X"), "");
        Files.createDirectories(input.resolve("com/example"));
        Files.writeString(input.resolve("com/example/X.class"), "x");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jar));

        List<String> entries = listEntries(jar);
        assertThat(entries)
                .contains(
                        "META-INF/",
                        "META-INF/micronaut/",
                        "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/",
                        "com/",
                        "com/example/");
        // Each directory appears exactly once.
        assertThat(entries.stream().filter("com/"::equals)).hasSize(1);
    }

    @Test
    void reproducible_byte_for_byte(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Hello.class"), "stable-content");

        Path jarA = tempDir.resolve("a.jar");
        Path jarB = tempDir.resolve("b.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jarA));
        // Bump mtime of source files between runs to make sure the packager
        // doesn't pick up wall-clock timestamps.
        Files.setLastModifiedTime(
                input.resolve("Hello.class"), FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jarB));

        assertThat(Files.readAllBytes(jarA)).isEqualTo(Files.readAllBytes(jarB));
    }

    @Test
    void manifest_entry_is_pinned_to_the_fixed_epoch_not_wall_clock(@TempDir Path tempDir) throws IOException {
        // Regression guard: the JarOutputStream(out, manifest) convenience
        // constructor stamps the manifest with the *current* time, so an
        // otherwise-identical jar churns every build. The manifest must carry
        // the same pinned timestamp as every other entry.
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Hello.class"), "x");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jar));

        try (JarFile jf = new JarFile(jar.toFile())) {
            long manifestTime = jf.getJarEntry("META-INF/MANIFEST.MF").getTime();
            long dataTime = jf.getJarEntry("Hello.class").getTime();
            assertThat(manifestTime)
                    .as("manifest pinned to the same fixed epoch as data entries")
                    .isEqualTo(dataTime);
        }
    }

    @Test
    void manifest_carries_only_what_we_set(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input);
        Files.writeString(input.resolve("x.txt"), "data");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jar).withMainClass("com.example.Main"));

        try (JarFile jf = new JarFile(jar.toFile())) {
            Attributes attrs = jf.getManifest().getMainAttributes();
            assertThat(attrs.getValue(Attributes.Name.MANIFEST_VERSION)).isEqualTo("1.0");
            assertThat(attrs.getValue(Attributes.Name.MAIN_CLASS)).isEqualTo("com.example.Main");
            // We deliberately don't write Created-By / Build-Jdk.
            assertThat(attrs.getValue("Created-By")).isNull();
            assertThat(attrs.getValue("Build-Jdk")).isNull();
        }
    }

    @Test
    void custom_manifest_attributes_are_written(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input);
        Files.writeString(input.resolve("x.txt"), "data");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager()
                .packageJar(JarPackager.JarRequest.of(input, jar)
                        .withMainClass("com.example.Main")
                        .withAttributes(Map.of(
                                "Implementation-Title", "widget",
                                "Implementation-Version", "1.0.0")));

        try (JarFile jf = new JarFile(jar.toFile())) {
            Attributes attrs = jf.getManifest().getMainAttributes();
            assertThat(attrs.getValue(Attributes.Name.MAIN_CLASS)).isEqualTo("com.example.Main");
            assertThat(attrs.getValue("Implementation-Title")).isEqualTo("widget");
            assertThat(attrs.getValue("Implementation-Version")).isEqualTo("1.0.0");
        }
    }

    @Test
    void manifest_attributes_are_written_in_name_order_whatever_order_the_map_iterates(@TempDir Path tempDir)
            throws IOException {
        // The attribute order is part of the jar's bytes. The request keeps its attributes in an
        // immutable copy whose iteration order is salted per JVM, so two engines packaging the same
        // classes would otherwise write two manifests; the packager settles the order by name.
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input);
        Files.writeString(input.resolve("x.txt"), "data");
        Map<String, String> scrambled = new LinkedHashMap<>();
        scrambled.put("Sbom-Location", "META-INF/sbom/application.cdx.json");
        scrambled.put("Implementation-Version", "1.0.0");
        scrambled.put("Sbom-Format", "CycloneDX");
        scrambled.put("Implementation-Title", "widget");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager()
                .packageJar(JarPackager.JarRequest.of(input, jar)
                        .withMainClass("com.example.Main")
                        .withAttributes(scrambled));

        assertThat(rawManifest(jar))
                .isEqualTo("Manifest-Version: 1.0\r\n"
                        + "Main-Class: com.example.Main\r\n"
                        + "Implementation-Title: widget\r\n"
                        + "Implementation-Version: 1.0.0\r\n"
                        + "Sbom-Format: CycloneDX\r\n"
                        + "Sbom-Location: META-INF/sbom/application.cdx.json\r\n"
                        + "\r\n");
    }

    private static String rawManifest(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile());
                InputStream in = jf.getInputStream(jf.getJarEntry("META-INF/MANIFEST.MF"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void entry_contents_round_trip(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input);
        Files.writeString(input.resolve("hello.txt"), "round-trip");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jar));

        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry entry = jf.getJarEntry("hello.txt");
            assertThat(entry).isNotNull();
            try (InputStream in = jf.getInputStream(entry)) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(content).isEqualTo("round-trip");
            }
        }
    }

    @Test
    void freshness_stamps_never_enter_the_jar(@TempDir Path tempDir) throws IOException {
        // A Groovy module's classes dir carries .gstamp (Java .jstamp, Kotlin .kstamp) beside the
        // classes. The stamp body is a wall clock, so shipping it both leaks build-host metadata
        // and makes two builds of identical sources differ byte-for-byte.
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input.resolve("com/example"));
        Files.writeString(input.resolve("com/example/Greeting.class"), "groovyc output");

        Path jarA = tempDir.resolve("a.jar");
        Path jarB = tempDir.resolve("b.jar");
        writeStamps(input, "STAMP_MILLIS 1000");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jarA));
        writeStamps(input, "STAMP_MILLIS 9999999999");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jarB));

        assertThat(listEntries(jarA))
                .as("build-host metadata, not jar content")
                .doesNotContain(BuildStamps.GROOVY, BuildStamps.JAVA, BuildStamps.KOTLIN);
        assertThat(Files.readAllBytes(jarA))
                .as("two builds of the same sources produce the same bytes")
                .isEqualTo(Files.readAllBytes(jarB));
    }

    /**
     * The package step keys on the classes tree's token and packs that tree. A template's
     * {@code .jk-template.toml} (or any other dot-file resource) is packed, so it has to move the
     * key: an edit to it alone must name a new jar, and the revert must name the original one
     * again, with the original bytes.
     */
    @Test
    void a_resource_dot_file_moves_the_jar_key_and_the_jar_and_its_revert_moves_both_back(@TempDir Path tempDir)
            throws Exception {
        Path module = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(module.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                """);
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        Path classes = module.resolve("target/classes");
        Files.createDirectories(classes.resolve("com/example"));
        Files.writeString(classes.resolve("com/example/Lib.class"), "javac output");
        Path template = classes.resolve("templates/java/app/default.g8/.jk-template.toml");
        Files.createDirectories(classes.resolve("templates/java/app/default.g8"));
        Files.writeString(template, "title = \"one\"\n");
        Files.writeString(classes.resolve(".DS_Store"), "finder");
        Path jar = module.resolve("target/lib-1.0.0.jar");

        Packaged original = pack(module, project, classes, jar);
        assertThat(entry(original.bytes(), "templates/java/app/default.g8/.jk-template.toml"))
                .isEqualTo("title = \"one\"\n");
        assertThat(entry(original.bytes(), ".DS_Store")).isEqualTo("finder");

        Files.writeString(template, "title = \"edited\"\n");
        Packaged edited = pack(module, project, classes, jar);
        assertThat(edited.key())
                .as("an edit to the dot-file alone names a new jar")
                .isNotEqualTo(original.key());
        assertThat(entry(edited.bytes(), "templates/java/app/default.g8/.jk-template.toml"))
                .isEqualTo("title = \"edited\"\n");

        Files.writeString(template, "title = \"one\"\n");
        Packaged reverted = pack(module, project, classes, jar);
        assertThat(reverted.key()).as("the revert names the original jar").isEqualTo(original.key());
        assertThat(reverted.bytes()).as("with the original bytes").isEqualTo(original.bytes());
    }

    private record Packaged(String key, byte[] bytes) {}

    /** The package step's key over {@code classes} as the live step derives it, and the jar it packs. */
    private static Packaged pack(Path module, JkBuild project, Path classes, Path jar) throws IOException {
        String key = PackagingKeys.packageJar(jar, module, project, ClasspathFingerprint.entry(classes), "", null)
                .key();
        new JarPackager().packageJar(JarPackager.JarRequest.of(classes, jar));
        return new Packaged(key, Files.readAllBytes(jar));
    }

    private static String entry(byte[] jar, String name) throws IOException {
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            for (JarEntry e = in.getNextJarEntry(); e != null; e = in.getNextJarEntry()) {
                if (e.getName().equals(name)) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " is not in the jar");
    }

    private static void writeStamps(Path classes, String body) throws IOException {
        Files.writeString(classes.resolve(BuildStamps.JAVA), body);
        Files.writeString(classes.resolve(BuildStamps.KOTLIN), body);
        Files.writeString(classes.resolve(BuildStamps.GROOVY), body);
    }

    private static List<String> listEntries(Path jar) throws IOException {
        List<String> result = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            jf.stream().forEach(e -> result.add(e.getName()));
        }
        return result;
    }
}
