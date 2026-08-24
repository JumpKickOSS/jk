// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * The boot-jar packager body, driven end to end over a fake {@link PackageIo} and asserted by
 * reading the jar it wrote. {@code [spring-boot] version} is a <em>selector</em> — the plugin's own
 * scaffolds all write {@code latest} — and it used to be copied verbatim into the manifest, so
 * {@code jk new -t spring-boot/hello && jk build} published a jar saying
 * {@code Spring-Boot-Version: latest}.
 */
class SpringBootPluginTest {

    @Test
    void the_manifest_records_the_resolved_boot_version_not_the_declared_selector(@TempDir Path tmp) throws Exception {
        FakeIo io = new FakeIo(tmp, Map.of("version", "latest", "include-tools", Boolean.FALSE));
        io.entry("spring-boot-4.1.2.jar", "org.springframework.boot", "spring-boot", "4.1.2");

        SpringBootPlugin.produceBootJar(io);

        try (JarFile jar = new JarFile(io.artifactPath().toFile())) {
            Attributes attrs = jar.getManifest().getMainAttributes();
            assertThat(attrs.getValue("Spring-Boot-Version"))
                    .as("the version the closure resolved to, never the declared selector")
                    .isEqualTo("4.1.2");
            assertThat(attrs.getValue("Start-Class")).isEqualTo("com.example.App");
        }
    }

    /**
     * The nested jarmode tools entry carried the same selector: {@code
     * BOOT-INF/lib/spring-boot-jarmode-tools-latest.jar}. It is now unversioned, because the tools
     * jar resolves against its own selector and its version need not equal the closure's.
     */
    @Test
    void the_jarmode_tools_entry_claims_no_version(@TempDir Path tmp) throws Exception {
        FakeIo io = new FakeIo(tmp, Map.of("version", "latest", "include-tools", Boolean.TRUE));
        io.entry("spring-boot-4.1.2.jar", "org.springframework.boot", "spring-boot", "4.1.2");
        io.extras.put("spring-boot-jarmode-tools", io.jar("tools.jar", "org/springframework/boot/jarmode/Tool.class"));

        SpringBootPlugin.produceBootJar(io);

        try (JarFile jar = new JarFile(io.artifactPath().toFile())) {
            assertThat(jar.stream()
                            .map(JarEntry::getName)
                            .filter(n -> n.startsWith("BOOT-INF/lib/") && !n.endsWith("/")))
                    .containsExactly("BOOT-INF/lib/spring-boot-4.1.2.jar", "BOOT-INF/lib/spring-boot-jarmode-tools.jar")
                    .noneMatch(n -> n.contains("latest"));
        }
    }

    /** No Boot in the closure means no Boot to launch — that fails here, not at `java -jar`. */
    @Test
    void a_closure_without_spring_boot_is_refused(@TempDir Path tmp) throws Exception {
        FakeIo io = new FakeIo(tmp, Map.of("version", "latest", "include-tools", Boolean.FALSE));
        io.entry("guava-33.0.jar", "com.google.guava", "guava", "33.0");

        assertThatThrownBy(() -> SpringBootPlugin.produceBootJar(io))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("org.springframework.boot:spring-boot");
    }

    /** A fake {@link PackageIo}: real files on disk, no engine, no network. */
    static final class FakeIo implements PackageIo {
        private final Path tmp;
        private final Map<String, Object> config;
        final Map<String, Path> extras = new LinkedHashMap<>();
        final List<RuntimeEntry> entries = new ArrayList<>();

        FakeIo(Path tmp, Map<String, Object> config) throws IOException {
            this.tmp = tmp;
            this.config = config;
            Files.createDirectories(tmp.resolve("classes/com/example"));
            Files.write(tmp.resolve("classes/com/example/App.class"), new byte[] {1, 2, 3});
            extras.put(
                    "spring-boot-loader",
                    jar("spring-boot-loader.jar", "org/springframework/boot/loader/launch/JarLauncher.class"));
        }

        /** Append a coordinate-named runtime entry backed by a real one-class jar. */
        void entry(String fileName, String group, String artifact, String version) throws IOException {
            entries.add(new RuntimeEntry(
                    fileName,
                    jar(fileName, artifact.replace('-', '/') + ".class"),
                    false,
                    null,
                    group,
                    artifact,
                    version));
        }

        Path jar(String name, String entryName) throws IOException {
            Path path = Files.createDirectories(tmp.resolve("blobs")).resolve(name);
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(new byte[] {0xC, 0xA});
                jos.closeEntry();
            }
            return path;
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
            return List.copyOf(entries);
        }

        @Override
        public PluginConfig config() {
            return new PluginConfig("spring-boot", config);
        }

        @Override
        public ProjectFacts project() {
            return new ProjectFacts("com.example", "app", "1.0.0", 25, "com.example.App", false, false, Map.of());
        }

        @Override
        public Optional<Path> stepOutput(String step) {
            return Optional.empty();
        }

        @Override
        public Optional<Path> extra(String name) {
            return Optional.ofNullable(extras.get(name));
        }

        @Override
        public Path artifactPath() {
            return tmp.resolve("target/app-1.0.0.jar");
        }

        @Override
        public Path javaHome() {
            return Path.of(System.getProperty("java.home"));
        }

        @Override
        public void label(String text) {}
    }
}
