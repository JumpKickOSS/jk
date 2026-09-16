// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace module the test model carries names the module directory and roots the main
 * source set at {@code target/classes/main} alone — the one class tree the test bootstrap indexes.
 */
class QuarkusTestModelMainTest {

    @Test
    void the_main_source_set_outputs_to_the_classes_dir_alone(@TempDir Path module) throws Exception {
        Files.createDirectories(module.resolve("src/main/java"));
        Files.createDirectories(module.resolve("src/main/resources"));
        Path classes = module.resolve("target/classes/main");

        WorkspaceModule ws = QuarkusTestModelMain.workspaceModule(module, classes, "com.ex", "svc", "1.0");

        assertThat(ws.getId().getGroupId()).isEqualTo("com.ex");
        assertThat(ws.getId().getArtifactId()).isEqualTo("svc");
        assertThat(ws.getModuleDir()).isEqualTo(module);
        assertThat(ws.getBuildDir()).isEqualTo(module.resolve("target"));
        assertThat(ws.getMainSources().getSourceDirs())
                .extracting(SourceDir::getOutputDir)
                .containsExactly(classes);
        assertThat(ws.getMainSources().getResourceDirs())
                .extracting(SourceDir::getOutputDir)
                .containsExactly(classes);
        assertThat(ws.getMainSources().getSourceDirs())
                .extracting(SourceDir::getDir)
                .containsExactly(module.resolve("src/main/java"));
        assertThat(ws.getTestSources())
                .as("the bootstrap finds test classes off the classpath")
                .isNull();
    }

    @Test
    void a_module_without_conventional_source_dirs_still_roots_the_classes_dir(@TempDir Path module) {
        Path classes = module.resolve("target/classes/main");
        WorkspaceModule ws = QuarkusTestModelMain.workspaceModule(module, classes, "com.ex", "svc", "1.0");
        assertThat(ws.getMainSources().getSourceDirs()).isEmpty();
        assertThat(ws.getMainSources().getResourceDirs()).isEmpty();
        assertThat(ws.getModuleDir()).isEqualTo(module);
    }
}
