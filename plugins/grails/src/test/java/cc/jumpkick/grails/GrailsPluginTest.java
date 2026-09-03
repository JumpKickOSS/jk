// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.grails;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The grails-jar packager body, driven over a fake {@link PackageIo} and asserted by reading the
 * jar it wrote. Grails ships a Boot-launcher jar, so it must record the resolved Boot line it
 * was built against, never the {@code boot-version} schema selector.
 */
class GrailsPluginTest {

    @Test
    void the_manifest_records_the_resolved_boot_version_and_the_grails_line(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = fake(tmp, Map.of("version", "8.0.0-M4", "boot-version", "4"));

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
    /**
     * The shared engine fake, plus the three inputs a Grails jar needs: a compiled application
     * class, the Boot loader jar the engine fetches as a packager dependency, and a closure whose
     * order is the classpath order the packager merges in. All of it is this plugin's fixture
     * <em>content</em>, so it stays here rather than in the shared fake.
     */
    private static FakeBuildIo fake(Path tmp, Map<String, Object> config) throws IOException {
        FakeBuildIo io = new FakeBuildIo(tmp, "grails")
                .config(config)
                .offline(false)
                .project("com.example", "gnotes", "1.0.0", "com.example.Application");
        FakeBuildIo.write(io.classesDir().resolve("com/example/Application.class"), "not-really-bytecode");
        io.extra(
                "spring-boot-loader",
                io.jar("spring-boot-loader.jar", "org/springframework/boot/loader/launch/JarLauncher.class"));
        io.entry(
                "grails-core-8.0.0-M4.jar", "org.apache.grails", "grails-core", "8.0.0-M4", "grails/core/Marker.class");
        io.entry(
                "spring-boot-4.1.2.jar",
                "org.springframework.boot",
                "spring-boot",
                "4.1.2",
                "org/springframework/boot/SpringApplication.class");
        return io;
    }
}
