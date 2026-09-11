// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.ide.IdeEngineClient;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.TestSelection;
import java.util.List;
import org.junit.jupiter.api.Test;

/** BSP data payload → TestSelection and the debug request (no engine). */
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

    @Test
    void no_debug_field_is_no_debug_request() {
        assertThat(BspServer.parseDebugData("{}")).isNull();
        assertThat(BspServer.parseDebugData("""
                        {"params":{"targets":[{"uri":"file:///p#m"}],"data":{"allSuites":true}}}
                        """)).isNull();
        assertThat(BspServer.parseDebugData("""
                        {"data":{"debug":false}}
                        """)).isNull();
        assertThat(BspServer.parseDebugData("not json")).isNull();
    }

    @Test
    void debug_true_is_the_stock_address_suspended() {
        String json = """
                {"params":{"targets":[{"uri":"file:///p#m"}],"data":{"debug":true}}}
                """;
        assertThat(BspServer.parseDebugData(json)).isEqualTo(DebugJvm.DEFAULT);
    }

    @Test
    void debug_object_carries_port_host_and_suspend() {
        String json = """
                {"data":{"suites":["test"],"debug":{"port":0,"suspend":false}}}
                """;
        DebugJvm d = BspServer.parseDebugData(json);
        assertThat(d).isEqualTo(new DebugJvm("localhost", 0, false));
        assertThat(DebugJvm.parse("0").portChosenByClient()).isTrue();

        DebugJvm all = BspServer.parseDebugData("""
                {"data":{"debug":{"host":"*","port":6006}}}
                """);
        assertThat(all).isEqualTo(new DebugJvm("*", 6006, true));
    }

    @Test
    void debug_string_is_the_cli_spec() {
        assertThat(BspServer.parseDebugData("""
                        {"data":{"debug":"0,suspend=n"}}
                        """)).isEqualTo(DebugJvm.parse("0,suspend=n"));
    }

    @Test
    void the_selection_beside_a_debug_request_is_read_unchanged() {
        String json = """
                {"data":{"suites":["integration"],"includeTags":["smoke"],"debug":true}}
                """;
        TestSelection s = BspServer.parseTestSelectionData(json);
        assertThat(s.suites()).containsExactly("integration");
        assertThat(s.includeTags()).containsExactly("smoke");
    }

    @Test
    void a_debugged_test_result_echoes_the_address_in_data() {
        var outcome = new IdeEngineClient.BuildOutcome(true, 1, 0, List.of());
        String json = BspServer.statusResult(outcome, "test failed", DebugJvm.parse("7007"));
        assertThat(json)
                .contains("\"statusCode\":1")
                .contains("\"dataKind\":\"jk-debug\"")
                .contains("\"address\":\"localhost:7007\"")
                .contains("\"port\":7007")
                .contains("\"suspend\":true");
        assertThat(BspServer.statusResult(outcome, "test failed", null)).doesNotContain("dataKind");
    }
}
