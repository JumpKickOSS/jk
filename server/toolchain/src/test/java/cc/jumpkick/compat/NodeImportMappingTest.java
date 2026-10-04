// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeImportMappingTest {

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void quinoa_is_a_side_by_side_build_served_from_meta_inf_resources(@TempDir Path module) throws IOException {
        write(module.resolve("src/main/resources/application.properties"), """
                quarkus.quinoa.package-manager-install=true
                quarkus.quinoa.package-manager-install.node-version=20.11.1
                quarkus.quinoa.ui-dir=src/main/ui
                quarkus.quinoa.build-dir=dist/app
                """);
        write(module.resolve("src/main/ui/package.json"), "{}");

        NodeImportMapping.Mapped m = Objects.requireNonNull(NodeImportMapping.quinoa(module, ImportReport.builder()));

        assertThat(m.spec().requiredVersion()).isEqualTo("20.11.1");
        assertThat(Objects.requireNonNull(m.table()).dir()).isEqualTo("src/main/ui");
        assertThat(m.table().classpathRoot()).isEqualTo("META-INF/resources");
        assertThat(m.table().out()).isEqualTo("dist/app");
    }

    @Test
    void quinoa_defaults_to_webui_and_the_version_its_files_suggest(@TempDir Path module) throws IOException {
        write(module.resolve("src/main/webui/package.json"), "{}");
        write(module.resolve("src/main/webui/.nvmrc"), "22\n");

        NodeImportMapping.Mapped m = Objects.requireNonNull(NodeImportMapping.quinoa(module, ImportReport.builder()));

        assertThat(m.spec().suggestedVersion()).isEqualTo("22");
        assertThat(Objects.requireNonNull(m.table()).dir()).isEqualTo("src/main/webui");
    }

    @Test
    void a_module_without_quinoa_maps_nothing(@TempDir Path module) {
        assertThat(NodeImportMapping.quinoa(module, ImportReport.builder())).isNull();
    }
}
