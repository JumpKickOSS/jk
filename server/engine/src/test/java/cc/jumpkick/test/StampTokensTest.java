// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.task.KeyAudit;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A forecast whose suite key misses names what moved since the last green run, so jk explain never
 * schedules a suite as a bare "run tests" when it can say why (JK-E7GKJ).
 */
class StampTokensTest {

    private static Map<String, String> tokens(String... lines) {
        return KeyAudit.lines("", List.of(lines));
    }

    private static final String SRC = "src:clients/cli/src/test/java/a/FooTest.java:" + "ab".repeat(32);
    private static final String CP = "cp:" + "cd".repeat(32);

    @Test
    void a_new_jk_is_named_before_the_inputs_it_moved_with() {
        var was = tokens(SRC, CP, "x:jk:0.52.0-SNAPSHOT#8+aaaaaaaaaaaa", "x:compile-test:" + "01".repeat(32));
        var now = tokens(SRC, CP, "x:jk:0.52.0-SNAPSHOT#8+bbbbbbbbbbbb", "x:compile-test:" + "02".repeat(32));
        assertThat(StampTokens.reason(was, now)).isEqualTo(StampTokens.JK_CHANGED);
    }

    @Test
    void an_input_that_moved_is_named_with_how_many_more_did() {
        var was = tokens(SRC, CP, "x:read:bench/agent-loop/README.md=" + "11".repeat(32));
        var now = tokens(
                "src:clients/cli/src/test/java/a/FooTest.java:" + "ef".repeat(32),
                CP,
                "x:read:bench/agent-loop/README.md=" + "22".repeat(32));
        assertThat(StampTokens.reason(was, now)).isEqualTo("test source a/FooTest.java changed (+1 more)");
        assertThat(StampTokens.reason(tokens(SRC, CP), tokens(SRC, "cp:" + "99".repeat(32))))
                .isEqualTo("runtime classpath changed");
        assertThat(StampTokens.describe("x:read:bench/agent-loop/README.md")).isEqualTo("agent-loop/README.md changed");
        assertThat(StampTokens.describe("x:worker:jk.test-runner.plugin.jar"))
                .isEqualTo("worker jk.test-runner.plugin.jar changed");
    }

    @Test
    void the_same_tokens_give_no_reason() {
        assertThat(StampTokens.reason(tokens(SRC, CP), tokens(SRC, CP))).isNull();
    }

    @Test
    void a_forecast_whose_compile_is_dirty_still_names_a_new_jk(@TempDir Path dir) {
        TestSelection sel = TestSelection.DEFAULT;
        assertThat(StampTokens.jkMoved(dir, sel, "0.52.0-SNAPSHOT#8+aaaaaaaaaaaa"))
                .isNull();
        StampTokens.keep(dir, sel, "k", () -> {
            KeyAudit.tap("x:jk:0.52.0-SNAPSHOT#8+aaaaaaaaaaaa");
            return "k";
        });
        assertThat(StampTokens.jkMoved(dir, sel, "0.52.0-SNAPSHOT#8+aaaaaaaaaaaa"))
                .isNull();
        assertThat(StampTokens.jkMoved(dir, sel, "0.52.0-SNAPSHOT#8+bbbbbbbbbbbb"))
                .isEqualTo(StampTokens.JK_CHANGED);
    }
}
