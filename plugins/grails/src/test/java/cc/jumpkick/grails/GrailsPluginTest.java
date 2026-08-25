// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.grails;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The grails-jar packager body, driven over a fake {@link PackageIo} and asserted by reading the
 * jar it wrote. Grails ships a Boot-launcher jar, so it must record the Boot line it was built
 * against; it used to record the literal {@code 4} — the {@code boot-version} schema default,
 * copied straight out of the config table — on every Grails jar ever produced.
 */
class GrailsPluginTest {

    @Test
    void the_manifest_records_the_resolved_boot_version_and_the_grails_line(@TempDir Path tmp) throws Exception {
        FakeIo io = new FakeIo(tmp, Map.of("version", "8.0.0-M4", "boot-version", "4"));

        GrailsPlugin.produceJar(io);

        try (JarFile jar = new JarFile(io.artifactPath().toFile())) {
            Attributes attrs = jar.getManifest().getMainAttributes();
            assertThat(attrs.getValue("Spring-Boot-Version"))
                    .as("the Boot version the closure resolved to, not the boot-version selector")
                    .isEqualTo("4.1.2");
            assertThat(attrs.getValue("Grails-Version")).isEqualTo("8.0.0-M4");
            assertThat(attrs.getValue("Main-Class")).isEqualTo("org.springframework.boot.loader.launch.JarLauncher");
            assertThat(attrs.getValue("Start-Class")).isEqualTo("com.example.Application");
            assertThat(jar.getEntry("BOOT-INF/classes/com/example/Application.class"))
                    .isNotNull();
            assertThat(jar.getEntry("BOOT-INF/lib/spring-boot-4.1.2.jar")).isNotNull();
        }
    }

    /** A fake {@link PackageIo}: real files on disk, no engine, no network. */
    private static final class FakeIo implements PackageIo {
        private final Path tmp;
        private final Map<String, Object> config;
        private final Path loader;
        private final List<RuntimeEntry> entries;

        FakeIo(Path tmp, Map<String, Object> config) throws IOException {
            this.tmp = tmp;
            this.config = config;
            Files.createDirectories(tmp.resolve("classes/com/example"));
            Files.write(tmp.resolve("classes/com/example/Application.class"), new byte[] {1, 2, 3});
            this.loader = jar("spring-boot-loader.jar", "org/springframework/boot/loader/launch/JarLauncher.class");
            this.entries = List.of(
                    new RuntimeEntry(
                            "grails-core-8.0.0-M4.jar",
                            jar("grails-core-8.0.0-M4.jar", "grails/core/Marker.class"),
                            false,
                            null,
                            "org.apache.grails",
                            "grails-core",
                            "8.0.0-M4"),
                    new RuntimeEntry(
                            "spring-boot-4.1.2.jar",
                            jar("spring-boot-4.1.2.jar", "org/springframework/boot/SpringApplication.class"),
                            false,
                            null,
                            "org.springframework.boot",
                            "spring-boot",
                            "4.1.2"));
        }

        private Path jar(String name, String entryName) throws IOException {
            Path path = Files.createDirectories(tmp.resolve("blobs")).resolve(name);
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(new byte[] {0xC, 0xA});
                jos.closeEntry();
            }
            return path;
        }

        @Override
        public boolean offline() {
            return false;
        }

        @Override
        public Path classesDir() {
            return tmp.resolve("classes");
        }

        @Override
        public Path moduleDir() {
            return tmp;
        }

        @Override
        public List<RuntimeEntry> runtimeEntries() {
            return entries;
        }

        @Override
        public PluginConfig config() {
            return new PluginConfig("grails", config);
        }

        @Override
        public ProjectFacts project() {
            return new ProjectFacts(
                    "com.example", "gnotes", "1.0.0", 25, "com.example.Application", false, false, Map.of());
        }

        @Override
        public Optional<Path> stepOutput(String step) {
            return Optional.empty();
        }

        @Override
        public Optional<Path> extra(String name) {
            return "spring-boot-loader".equals(name) ? Optional.of(loader) : Optional.empty();
        }

        @Override
        public Path artifactPath() {
            return tmp.resolve("target/gnotes-1.0.0.jar");
        }

        @Override
        public Path javaHome() {
            return Path.of(System.getProperty("java.home"));
        }

        @Override
        public void label(String text) {}
    }
}
