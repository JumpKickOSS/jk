// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
     * The nested jarmode tools entry carried the same selector: {@code
     * BOOT-INF/lib/spring-boot-jarmode-tools-latest.jar}. It is now unversioned, because the tools
     * jar resolves against its own selector and its version need not equal the closure's.
     */
    @Test
    void the_jarmode_tools_entry_claims_no_version(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = fake(tmp, Map.of("version", "latest", "include-tools", Boolean.TRUE));
        io.entry("spring-boot-4.1.2.jar", "org.springframework.boot", "spring-boot", "4.1.2");
        io.extra("spring-boot-jarmode-tools", io.jar("tools.jar", "org/springframework/boot/jarmode/Tool.class"));

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
