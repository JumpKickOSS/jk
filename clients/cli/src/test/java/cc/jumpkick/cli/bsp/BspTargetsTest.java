// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BspTargetsTest {

    @Test
    void a_build_with_no_module_list_is_one_target_displayed_by_its_coordinate(@TempDir Path dir) {
        String rootUri = BspServer.pathUri(dir);

        String json = BspTargets.json(null, dir, "g:demo");

        assertThat(json)
                .startsWith("{\"targets\":[")
                .contains("\"id\":{\"uri\":\"" + rootUri + "#root\"}")
                .contains("\"displayName\":\"g:demo\"")
                .contains("\"tags\":[\"library\"]");
    }

    @Test
    void a_root_without_a_coordinate_is_displayed_as_root(@TempDir Path dir) {
        assertThat(BspTargets.json(null, dir, " ")).contains("\"displayName\":\"root\"");
        assertThat(BspTargets.json(null, dir, null)).contains("\"displayName\":\"root\"");
    }

    @Test
    void a_suite_target_id_names_its_suite_and_a_module_target_none() {
        assertThat(BspTargets.suiteOf("file:///p#app" + BspTargets.SUITE_SEPARATOR + "it"))
                .isEqualTo("it");
        assertThat(BspTargets.suiteOf("file:///p#app")).isNull();
    }
}
