// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ReleaseDeliverables;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A framework app's release carries the jar its packager writes when that jar runs by itself: the
 * Spring Boot and Grails executable jars and a Quarkus uber-jar, named as on disk. Quarkus's
 * default fast-jar runs from {@code quarkus-app/}, which has no archive, so it ships nothing.
 */
class ModuleJarReleaseFilesTest {

    private static final String HEADER = """
            name = "svc"
            group = "com.example"
            version = "1.0.0"
            java = 25
            """;

    @TempDir
    Path dir;

    private ReleaseDeliverables.Deliverable only(String table) {
        JkBuild build = JkBuildParser.parse(HEADER + table);
        var files = ModuleJar.releaseFiles(build, dir);
        assertThat(files).hasSize(1);
        ReleaseDeliverables.Deliverable d = files.getFirst();
        Path jar = BuildLayout.of(dir, build).mainJar();
        assertThat(d.file()).isEqualTo(jar);
        assertThat(d.name()).isEqualTo(jar.getFileName().toString());
        assertThat(d.neutral()).isTrue();
        assertThat(d.module()).isEqualTo("svc");
        return d;
    }

    @Test
    void a_spring_boot_app_ships_its_boot_jar() {
        only("""
                [spring-boot]
                version = "4.1.1"
                """);
    }

    @Test
    void a_grails_app_ships_its_boot_layout_jar() {
        only("""
                [grails]
                version = "8.0.0"
                """);
    }

    @Test
    void a_quarkus_uber_jar_ships_and_the_default_fast_jar_does_not() {
        only("""
                [quarkus]
                version = "3.38.0"
                package = "uber-jar"
                """);
        JkBuild fastJar = JkBuildParser.parse(HEADER + """
                [quarkus]
                version = "3.38.0"
                """);
        assertThat(ModuleJar.releaseFiles(fastJar, dir)).isEmpty();
    }

    @Test
    void a_plain_library_ships_nothing() {
        assertThat(ModuleJar.releaseFiles(JkBuildParser.parse(HEADER), dir)).isEmpty();
    }
}
