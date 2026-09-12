// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boot-jar packager body, driven end to end over a fake {@link PackageIo} and asserted by
 * reading the jar it wrote. {@code [spring-boot] version} is a <em>selector</em> — the plugin's
 * own scaffolds all write {@code latest} — so the manifest records the resolved Boot version,
 * never the declared selector.
 */
class SpringBootPluginTest {

    @Test
    void the_manifest_records_the_resolved_boot_version_not_the_declared_selector(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = fake(tmp, Map.of("version", "latest", "include-tools", Boolean.FALSE));
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
     * The nested tools entry is named the way {@code bootJar} names it, {@code
     * spring-boot-jarmode-tools-<version>.jar}, with the version the jar itself states in its
     * manifest. The declared {@code [spring-boot] version} is a selector and never reaches the name.
     */
    @Test
    void the_jarmode_tools_entry_carries_the_version_its_own_manifest_states(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = fake(tmp, Map.of("version", "latest", "include-tools", Boolean.TRUE));
        io.entry("spring-boot-4.1.2.jar", "org.springframework.boot", "spring-boot", "4.1.2");
        Path tools = tmp.resolve("blobs/0123abcd");
        Files.createDirectories(tools.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "4.1.1");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tools), manifest)) {
            jos.putNextEntry(new JarEntry("org/springframework/boot/jarmode/tools/Tool.class"));
            jos.closeEntry();
        }
        io.extra("spring-boot-jarmode-tools", tools);

        SpringBootPlugin.produceBootJar(io);

        try (JarFile jar = new JarFile(io.artifactPath().toFile())) {
            assertThat(jar.stream()
                            .map(JarEntry::getName)
                            .filter(n -> n.startsWith("BOOT-INF/lib/") && !n.endsWith("/")))
                    .containsExactly(
                            "BOOT-INF/lib/spring-boot-4.1.2.jar", "BOOT-INF/lib/spring-boot-jarmode-tools-4.1.1.jar");
        }
    }

    /** A tools jar whose manifest states no version is nested without claiming one. */
    @Test
    void a_tools_jar_without_an_implementation_version_is_nested_unversioned(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = fake(tmp, Map.of("version", "latest", "include-tools", Boolean.TRUE));
        io.entry("spring-boot-4.1.2.jar", "org.springframework.boot", "spring-boot", "4.1.2");
        io.extra("spring-boot-jarmode-tools", io.jar("tools.jar", "org/springframework/boot/jarmode/Tool.class"));

        SpringBootPlugin.produceBootJar(io);

        try (JarFile jar = new JarFile(io.artifactPath().toFile())) {
            assertThat(jar.getEntry("BOOT-INF/lib/spring-boot-jarmode-tools.jar"))
                    .isNotNull();
        }
    }

    /** No Boot in the closure means no Boot to launch — that fails here, not at `java -jar`. */
    @Test
    void a_closure_without_spring_boot_is_refused(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = fake(tmp, Map.of("version", "latest", "include-tools", Boolean.FALSE));
        io.entry("guava-33.0.jar", "com.google.guava", "guava", "33.0");

        assertThatThrownBy(() -> SpringBootPlugin.produceBootJar(io))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("org.springframework.boot:spring-boot");
    }

    /**
     * The shared engine fake, plus the two inputs a boot jar cannot be built without: a compiled
     * application class, and the loader jar the engine fetches as a packager dependency. Both are
     * this plugin's fixture <em>content</em>, so they stay here rather than in the shared fake.
     */
    private static FakeBuildIo fake(Path tmp, Map<String, Object> config) throws IOException {
        FakeBuildIo io = new FakeBuildIo(tmp, "spring-boot")
                .config(config)
                .project("com.example", "app", "1.0.0", "com.example.App");
        FakeBuildIo.write(io.classesDir().resolve("com/example/App.class"), "not-really-bytecode");
        return io.extra(
                "spring-boot-loader",
                io.jar("spring-boot-loader.jar", "org/springframework/boot/loader/launch/JarLauncher.class"));
    }
}
