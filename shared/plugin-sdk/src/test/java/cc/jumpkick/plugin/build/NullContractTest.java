// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Representative nullable SPI values retain their documented Java shape. */
class NullContractTest {

    @Test
    void optional_result_and_runtime_entry_paths_are_explicit() {
        ImageResult pushed = ImageResult.pushed("registry.example/app:1");
        ImageResult tarball = ImageResult.tarball(Path.of("app.tar"));
        PackageIo.RuntimeEntry container = new PackageIo.RuntimeEntry("app.aar", null, false, Path.of("app-aar"));

        assertThat(pushed.reference()).isEqualTo("registry.example/app:1");
        assertThat(pushed.tarball()).isNull();
        assertThat(tarball.reference()).isNull();
        assertThat(tarball.tarball()).isEqualTo(Path.of("app.tar"));
        assertThat(container.jar()).isNull();
        assertThat(container.container()).isEqualTo(Path.of("app-aar"));
    }

    @Test
    void optional_declaration_values_accept_absence() {
        TaskSpec task = TaskSpec.named("compile").stage(null);
        PluginCommandSpec command = PluginCommandSpec.named("doctor").description(null);

        assertThat(task.stage()).isNull();
        assertThat(task.body()).isNull();
        assertThat(command.description()).isEmpty();
        assertThat(command.body()).isNull();
        assertThat(In.classes().step()).isNull();
        assertThat(InvocationPhase.fromWireOrNull(null)).isNull();
    }
}
