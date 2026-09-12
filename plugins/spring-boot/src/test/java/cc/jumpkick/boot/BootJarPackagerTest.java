// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.BuildStamps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootJarPackagerTest {

    @Test
    void produces_the_boot_executable_layout(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.createDirectories(classes.resolve("com/example"));
        Files.write(classes.resolve("com/example/App.class"), new byte[] {1, 2, 3});
        Files.write(classes.resolve("application.properties"), "server.port=8080".getBytes(StandardCharsets.UTF_8));

        Path dep = writeJar(tmp.resolve("spring-core-7.0.1.jar"), "org/springframework/core/Marker.class");
        Path snap = writeJar(tmp.resolve("acme-1.0-SNAPSHOT.jar"), "com/acme/Marker.class");
        Path loader = writeJar(
                tmp.resolve("spring-boot-loader-4.0.0.jar"),
                "org/springframework/boot/loader/launch/JarLauncher.class");

        Path out = tmp.resolve("app.jar");
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        classes,
                        List.of(
                                new BootJarPackager.Lib("spring-core-7.0.1.jar", dep, false, "org.springframework"),
                                new BootJarPackager.Lib("acme-1.0-SNAPSHOT.jar", snap, true, "com.acme")),
                        loader,
                        out,
                        "com.example.App",
                        "4.0.0",
                        Map.of("Implementation-Title", "app"),
                        Map.of(),
                        new byte[0],
                        List.of(),
                        0L));

        try (JarFile jar = new JarFile(out.toFile())) {
            Attributes attrs = jar.getManifest().getMainAttributes();
            assertThat(attrs.getValue("Main-Class")).isEqualTo("org.springframework.boot.loader.launch.JarLauncher");
            assertThat(attrs.getValue("Start-Class")).isEqualTo("com.example.App");
            assertThat(attrs.getValue("Spring-Boot-Version")).isEqualTo("4.0.0");
            assertThat(attrs.getValue("Spring-Boot-Classes")).isEqualTo("BOOT-INF/classes/");
            assertThat(attrs.getValue("Spring-Boot-Lib")).isEqualTo("BOOT-INF/lib/");
            assertThat(attrs.getValue("Spring-Boot-Classpath-Index")).isEqualTo("BOOT-INF/classpath.idx");
            assertThat(attrs.getValue("Spring-Boot-Layers-Index")).isEqualTo("BOOT-INF/layers.idx");
            assertThat(attrs.getValue("Implementation-Title")).isEqualTo("app");

            // Loader exploded at the root; app content under BOOT-INF/classes/.
            assertThat(jar.getEntry("org/springframework/boot/loader/launch/JarLauncher.class"))
                    .isNotNull();
            assertThat(jar.getEntry("BOOT-INF/classes/com/example/App.class")).isNotNull();
            assertThat(jar.getEntry("BOOT-INF/classes/application.properties")).isNotNull();

            // Nested jars are STORED — Boot's loader random-accesses them.
            JarEntry nested = (JarEntry) jar.getEntry("BOOT-INF/lib/spring-core-7.0.1.jar");
            assertThat(nested).isNotNull();
            assertThat(nested.getMethod()).isEqualTo(ZipEntry.STORED);

            String classpathIdx = new String(
                    jar.getInputStream(jar.getEntry("BOOT-INF/classpath.idx")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(classpathIdx)
                    .isEqualTo("- \"BOOT-INF/lib/spring-core-7.0.1.jar\"\n"
                            + "- \"BOOT-INF/lib/acme-1.0-SNAPSHOT.jar\"\n");

            String layersIdx = new String(
                    jar.getInputStream(jar.getEntry("BOOT-INF/layers.idx")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(layersIdx)
                    .isEqualTo("- \"dependencies\":\n"
                            + "  - \"BOOT-INF/lib/spring-core-7.0.1.jar\"\n"
                            + "- \"spring-boot-loader\":\n"
                            + "  - \"org/\"\n"
                            + "- \"snapshot-dependencies\":\n"
                            + "  - \"BOOT-INF/lib/acme-1.0-SNAPSHOT.jar\"\n"
                            + "- \"application\":\n"
                            + "  - \"BOOT-INF/classes/\"\n"
                            + "  - \"BOOT-INF/classpath.idx\"\n"
                            + "  - \"BOOT-INF/layers.idx\"\n"
                            + "  - \"META-INF/\"\n");
        }
    }

    @Test
    void compile_freshness_stamps_never_reach_boot_inf_classes(@TempDir Path tmp) throws Exception {
        // A stamp body is a wall clock, so a boot jar carrying one is neither clean nor
        // byte-reproducible.
        Path classes = Files.createDirectories(tmp.resolve("classes/com/example"));
        Files.write(classes.resolve("App.class"), new byte[] {1, 2, 3});
        for (String stamp : BuildStamps.ALL) {
            Files.writeString(tmp.resolve("classes").resolve(stamp), "STAMP_MILLIS 1758000000000");
        }
        Path loader = writeJar(
                tmp.resolve("spring-boot-loader-4.0.0.jar"),
                "org/springframework/boot/loader/launch/JarLauncher.class");

        Path out = tmp.resolve("app.jar");
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        tmp.resolve("classes"),
                        List.of(),
                        loader,
                        out,
                        "com.example.App",
                        "4.0.0",
                        Map.of(),
                        Map.of(),
                        new byte[0],
                        List.of(),
                        0L));

        try (JarFile jar = new JarFile(out.toFile())) {
            assertThat(jar.getEntry("BOOT-INF/classes/com/example/App.class")).isNotNull();
            for (String stamp : BuildStamps.ALL) {
                assertThat(jar.getEntry("BOOT-INF/classes/" + stamp)).as(stamp).isNull();
            }
        }
    }

    /**
     * Two coordinates can ship the same {@code artifact-version.jar}, and the nested entry names
     * must differ. The group is what differs, and a reader can act on it. The disambiguator used to
     * prepend {@code jar().getParent().getFileName()} instead — on the CAS layout production
     * actually serves ({@code <store>/sha256/AB/CD/<60 hex>}) that is two hex characters, so a real
     * collision produced {@code CD-util-1.0.jar}: unique by accident, meaningless to a reader, and
     * content-derived, so bumping either dependency renamed the entry.
     */
    @Test
    void colliding_lib_file_names_are_disambiguated_by_coordinate_group(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path a = writeJar(casBlob(tmp, "aa", "bb"), "a/A.class");
        Path b = writeJar(casBlob(tmp, "cc", "dd"), "b/B.class");
        Path c = writeJar(casBlob(tmp, "ee", "dd"), "c/C.class"); // same second shard pair as b
        Path sibling = writeJar(tmp.resolve("util-1.0.jar"), "d/D.class"); // workspace: no coordinate
        Path loader = writeJar(tmp.resolve("loader.jar"), "org/springframework/boot/loader/launch/JarLauncher.class");

        Path out = tmp.resolve("app.jar");
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        classes,
                        List.of(
                                new BootJarPackager.Lib("util-1.0.jar", a, false, "com.example.a"),
                                new BootJarPackager.Lib("util-1.0.jar", b, false, "com.example.b"),
                                new BootJarPackager.Lib("util-1.0.jar", c, false, "com.example.b"),
                                new BootJarPackager.Lib("util-1.0.jar", sibling, false, "")),
                        loader,
                        out,
                        "com.example.App",
                        "4.0.0",
                        Map.of(),
                        Map.of(),
                        new byte[0],
                        List.of(),
                        0L));

        try (JarFile jar = new JarFile(out.toFile())) {
            assertThat(jar.getEntry("BOOT-INF/lib/util-1.0.jar")).isNotNull(); // first keeps the plain name
            assertThat(jar.getEntry("BOOT-INF/lib/com.example.b-util-1.0.jar")).isNotNull();
            // Same group twice: the group cannot separate them, so an ordinal says so out loud.
            assertThat(jar.getEntry("BOOT-INF/lib/com.example.b-util-1.0.jar.2"))
                    .isNotNull();
            // A workspace sibling has no coordinate to borrow.
            assertThat(jar.getEntry("BOOT-INF/lib/dup-util-1.0.jar")).isNotNull();
            // No entry name may carry a CAS shard — that is what this replaced.
            assertThat(jar.stream().map(JarEntry::getName).filter(n -> n.startsWith("BOOT-INF/lib/")))
                    .noneMatch(n -> n.contains("/dd-") || n.contains("/bb-"));
            // …and every lib is listed exactly once in the index the launcher reads.
            assertThat(entryText(jar, "BOOT-INF/classpath.idx"))
                    .isEqualTo("- \"BOOT-INF/lib/util-1.0.jar\"\n"
                            + "- \"BOOT-INF/lib/com.example.b-util-1.0.jar\"\n"
                            + "- \"BOOT-INF/lib/com.example.b-util-1.0.jar.2\"\n"
                            + "- \"BOOT-INF/lib/dup-util-1.0.jar\"\n");
        }
    }

    /**
     * The manifest attribute is a version a consumer reads. Writing the declared selector into it
     * shipped jars announcing {@code Spring-Boot-Version: latest}; the packager now refuses the
     * value rather than publishing it.
     */
    @Test
    void a_selector_is_refused_as_the_boot_version(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path loader = writeJar(tmp.resolve("loader.jar"), "org/springframework/boot/loader/launch/JarLauncher.class");
        for (String selector : List.of("latest", "^4", "=4.1.0", "~4.1", "")) {
            assertThatThrownBy(() -> new BootJarPackager.BootJarRequest(
                            classes,
                            List.of(),
                            loader,
                            tmp.resolve("app.jar"),
                            "com.example.App",
                            selector,
                            Map.of(),
                            Map.of(),
                            new byte[0],
                            List.of(),
                            0L))
                    .as("selector %s", selector)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resolved version");
        }
    }

    /** A content-addressed blob path: {@code <store>/sha256/<hi>/<lo>/<name>}, as the CAS lays out. */
    private static Path casBlob(Path tmp, String hi, String lo) throws IOException {
        Path dir = tmp.resolve("store/sha256").resolve(hi).resolve(lo);
        Files.createDirectories(dir);
        return dir.resolve(hi + lo + "0".repeat(56));
    }

    @Test
    void embeds_build_info_and_sbom_when_supplied(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path loader = writeJar(tmp.resolve("loader.jar"), "org/springframework/boot/loader/launch/JarLauncher.class");
        // The packager treats the SBOM as opaque bytes (the engine renders CycloneDX upstream).
        byte[] sbom = ("{\"bomFormat\": \"CycloneDX\", \"components\": [{"
                        + "\"purl\": \"pkg:maven/org.springframework/spring-core@7.0.1\","
                        + " \"hashes\": [{\"alg\": \"SHA-256\", \"content\": \"abc123\"}]}]}")
                .getBytes(StandardCharsets.UTF_8);

        Path out = tmp.resolve("app.jar");
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        classes,
                        List.of(),
                        loader,
                        out,
                        "com.example.App",
                        "4.0.0",
                        Map.of(),
                        Map.of("group", "com.example", "artifact", "shop", "name", "shop", "version", "1.0.0"),
                        sbom,
                        List.of(),
                        0L));

        try (JarFile jar = new JarFile(out.toFile())) {
            String buildInfo = new String(
                    jar.getInputStream(jar.getEntry("BOOT-INF/classes/META-INF/build-info.properties"))
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(buildInfo)
                    .isEqualTo("build.artifact=shop\n"
                            + "build.group=com.example\n"
                            + "build.name=shop\n"
                            + "build.version=1.0.0\n");

            String sbomJson = new String(
                    jar.getInputStream(jar.getEntry("BOOT-INF/classes/META-INF/sbom/application.cdx.json"))
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(sbomJson).contains("\"bomFormat\": \"CycloneDX\"");
            assertThat(sbomJson).contains("\"purl\": \"pkg:maven/org.springframework/spring-core@7.0.1\"");
            assertThat(sbomJson).contains("\"content\": \"abc123\"");

            Attributes attrs = jar.getManifest().getMainAttributes();
            assertThat(attrs.getValue("Sbom-Format")).isEqualTo("CycloneDX");
            assertThat(attrs.getValue("Sbom-Location"))
                    .isEqualTo("BOOT-INF/classes/META-INF/sbom/application.cdx.json");
        }
    }

    /**
     * Boot's Gradle and Maven plugins leave a jar out of {@code BOOT-INF/lib} when its manifest's
     * {@code Spring-Boot-Jar-Type} names a starter, an annotation processor or a development tool:
     * a starter carries dependencies and no classes, the other two never belong at runtime. jk
     * applies the same rule, so the jar and both indexes agree with {@code bootJar}.
     */
    @Test
    void jars_boot_would_not_nest_are_left_out_of_lib_and_both_indexes(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path loader = writeJar(tmp.resolve("loader.jar"), "org/springframework/boot/loader/launch/JarLauncher.class");
        Path core = writeJar(tmp.resolve("spring-core-7.0.1.jar"), "org/springframework/core/Marker.class");
        Path starter = writeJar(
                tmp.resolve("spring-boot-starter-webmvc-4.1.1.jar"),
                Map.of("Spring-Boot-Jar-Type", "dependencies-starter"),
                "META-INF/NOTICE.txt");
        Path processor = writeJar(
                tmp.resolve("spring-boot-configuration-processor-4.1.1.jar"),
                Map.of("Spring-Boot-Jar-Type", "annotation-processor"),
                "org/springframework/boot/configurationprocessor/Processor.class");
        Path devtools = writeJar(
                tmp.resolve("spring-boot-devtools-4.1.1.jar"),
                Map.of("Spring-Boot-Jar-Type", "development-tool"),
                "org/springframework/boot/devtools/Marker.class");
        Path typed = writeJar(
                tmp.resolve("acme-lib-1.0.jar"), Map.of("Spring-Boot-Jar-Type", "library"), "com/acme/Marker.class");

        Path out = tmp.resolve("app.jar");
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        classes,
                        List.of(
                                new BootJarPackager.Lib("spring-core-7.0.1.jar", core, false, "org.springframework"),
                                new BootJarPackager.Lib(
                                        "spring-boot-starter-webmvc-4.1.1.jar",
                                        starter,
                                        false,
                                        "org.springframework.boot"),
                                new BootJarPackager.Lib(
                                        "spring-boot-configuration-processor-4.1.1.jar",
                                        processor,
                                        false,
                                        "org.springframework.boot"),
                                new BootJarPackager.Lib(
                                        "spring-boot-devtools-4.1.1.jar", devtools, false, "org.springframework.boot"),
                                new BootJarPackager.Lib("acme-lib-1.0.jar", typed, false, "com.acme")),
                        loader,
                        out,
                        "com.example.App",
                        "4.1.1",
                        Map.of(),
                        Map.of(),
                        new byte[0],
                        List.of(),
                        0L));

        try (JarFile jar = new JarFile(out.toFile())) {
            assertThat(jar.stream()
                            .map(JarEntry::getName)
                            .filter(n -> n.startsWith("BOOT-INF/lib/") && !n.endsWith("/")))
                    .containsExactly("BOOT-INF/lib/spring-core-7.0.1.jar", "BOOT-INF/lib/acme-lib-1.0.jar");
            assertThat(entryText(jar, "BOOT-INF/classpath.idx"))
                    .isEqualTo("- \"BOOT-INF/lib/spring-core-7.0.1.jar\"\n" + "- \"BOOT-INF/lib/acme-lib-1.0.jar\"\n");
            assertThat(entryText(jar, "BOOT-INF/layers.idx"))
                    .doesNotContain("starter", "configuration-processor", "devtools")
                    .contains(
                            "  - \"BOOT-INF/lib/spring-core-7.0.1.jar\"\n" + "  - \"BOOT-INF/lib/acme-lib-1.0.jar\"\n");
        }
    }

    /**
     * The loader registers its {@code nested:} filesystem through {@code
     * META-INF/services/java.nio.file.spi.FileSystemProvider}; without that file {@code
     * -Djarmode=tools extract} cannot open the nested jars. Boot's plugins copy the loader's
     * classes and its service registrations and nothing else from its META-INF, and so does jk.
     */
    @Test
    void the_exploded_loader_keeps_its_service_registrations_and_nothing_else_from_meta_inf(@TempDir Path tmp)
            throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        String provider = "org.springframework.boot.loader.nio.file.NestedFileSystemProvider\n";
        Path loader = tmp.resolve("spring-boot-loader-4.1.1.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(loader))) {
            for (String name : List.of(
                    "META-INF/MANIFEST.MF",
                    "META-INF/LICENSE.txt",
                    "META-INF/NOTICE.txt",
                    "org/springframework/boot/loader/launch/JarLauncher.class")) {
                jos.putNextEntry(new JarEntry(name));
                jos.write(new byte[] {0xC, 0xA});
                jos.closeEntry();
            }
            jos.putNextEntry(new JarEntry("META-INF/services/java.nio.file.spi.FileSystemProvider"));
            jos.write(provider.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path out = tmp.resolve("app.jar");
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        classes,
                        List.of(),
                        loader,
                        out,
                        "com.example.App",
                        "4.1.1",
                        Map.of(),
                        Map.of(),
                        new byte[0],
                        List.of(),
                        0L));

        try (JarFile jar = new JarFile(out.toFile())) {
            assertThat(entryText(jar, "META-INF/services/java.nio.file.spi.FileSystemProvider"))
                    .isEqualTo(provider);
            assertThat(jar.getEntry("META-INF/services/"))
                    .as("directory entry, as bootJar writes it")
                    .isNotNull();
            assertThat(jar.getEntry("org/springframework/boot/loader/launch/JarLauncher.class"))
                    .isNotNull();
            assertThat(jar.getEntry("META-INF/LICENSE.txt")).isNull();
            assertThat(jar.getEntry("META-INF/NOTICE.txt")).isNull();
            // The archive's manifest is jk's own, not the loader's.
            assertThat(jar.getManifest().getMainAttributes().getValue("Start-Class"))
                    .isEqualTo("com.example.App");
        }
    }

    /** UTF-8 text of one jar entry. */
    private static String entryText(JarFile jar, String name) throws IOException {
        return new String(jar.getInputStream(jar.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void build_info_entries_with_separators_and_spaces_survive_properties_load(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path loader = writeJar(tmp.resolve("loader.jar"), "org/springframework/boot/loader/launch/JarLauncher.class");

        Path out = tmp.resolve("app.jar");
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        classes,
                        List.of(),
                        loader,
                        out,
                        "com.example.App",
                        "4.0.0",
                        Map.of(),
                        Map.of(
                                "built by", "dev=ops:team \\ crew",
                                "notes", " leading space and #hash",
                                "revision", "line1\nline2"),
                        new byte[0],
                        List.of(),
                        0L));

        Properties loaded = new Properties();
        try (JarFile jar = new JarFile(out.toFile())) {
            loaded.load(jar.getInputStream(jar.getEntry("BOOT-INF/classes/META-INF/build-info.properties")));
        }
        assertThat(loaded.getProperty("build.built by")).isEqualTo("dev=ops:team \\ crew");
        assertThat(loaded.getProperty("build.notes")).isEqualTo(" leading space and #hash");
        assertThat(loaded.getProperty("build.revision")).isEqualTo("line1\nline2");
    }

    private static Path writeJar(Path path, String entryName) throws IOException {
        return writeJar(path, Map.of(), entryName);
    }

    /** A minimal jar: a manifest carrying {@code attributes} when any, plus one entry. */
    private static Path writeJar(Path path, Map<String, String> attributes, String entryName) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
            if (!attributes.isEmpty()) {
                Manifest manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                attributes.forEach((k, v) -> manifest.getMainAttributes().put(new Attributes.Name(k), v));
                jos.putNextEntry(new JarEntry(JarFile.MANIFEST_NAME));
                manifest.write(jos);
                jos.closeEntry();
            }
            jos.putNextEntry(new JarEntry(entryName));
            jos.write(new byte[] {0xC, 0xA});
            jos.closeEntry();
        }
        return path;
    }
}
