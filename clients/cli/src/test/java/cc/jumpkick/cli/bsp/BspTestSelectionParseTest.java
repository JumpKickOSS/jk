// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import org.junit.jupiter.api.Test;

/**BSP data payload → TestSelection (no engine). */
class BspTestSelectionParseTest {

    @Test
    void missing_data_is_default() {
        assertThat(BspServer.parseTestSelectionData("{}")).isEqualTo(TestSelection.DEFAULT);
    }

    @Test
    void data_object_all_suites_and_tags() {
        String json = """
                {"params":{"targets":[{"uri":"file:///p#m"}],"data":{"allSuites":true,"excludeTags":["slow","bench"]}}}
                """;
        TestSelection s = BspServer.parseTestSelectionData(json);
        assertThat(s.allSuites()).isTrue();
        assertThat(s.excludeTags()).containsExactly("slow", "bench");
    }

    @Test
    void data_suites_list() {
        String json = """
                {"data":{"suites":["integration","test"],"includeTags":["smoke"]}}
                """;
        TestSelection s = BspServer.parseTestSelectionData(json);
        assertThat(s.suites()).containsExactly("integration", "test");
        assertThat(s.includeTags()).containsExactly("smoke");
        assertThat(s.allSuites()).isFalse();
    }
}
