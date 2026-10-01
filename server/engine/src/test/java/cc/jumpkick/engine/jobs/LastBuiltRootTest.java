// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A job is noted by the workspace root that owns its directory, and a change of root is reported. */
class LastBuiltRootTest {

    @Test
    void a_module_of_the_same_workspace_is_no_switch_and_another_workspace_names_the_one_left(@TempDir Path dir)
            throws Exception {
        Path shop = dir.resolve("shop");
        Files.createDirectories(shop.resolve("api"));
        Files.writeString(shop.resolve("jk.toml"), "[workspace]\nmodules = [\"api\"]\n");
        Files.writeString(shop.resolve("api/jk.toml"), "name = \"api\"\nversion = \"1.0\"\n");
        Path other = Files.createDirectories(dir.resolve("other"));

        LastBuiltRoot.note(shop.toString());
        assertThat(LastBuiltRoot.get()).isEqualTo(shop);

        assertThat(LastBuiltRoot.note(shop.resolve("api").toString()))
                .as("a module job stays in its workspace")
                .isNull();
        assertThat(LastBuiltRoot.get()).isEqualTo(shop);

        assertThat(LastBuiltRoot.note(other.toString())).isEqualTo(shop);
        assertThat(LastBuiltRoot.get()).isEqualTo(other);

        assertThat(LastBuiltRoot.note(" ")).as("a blank dir keeps the note").isNull();
        assertThat(LastBuiltRoot.get()).isEqualTo(other);
    }
}
