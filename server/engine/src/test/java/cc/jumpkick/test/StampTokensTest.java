// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.task.KeyAudit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A forecast whose suite key misses names what moved since the last green run, so jk explain never
 * schedules a suite as a bare "run tests" when it can say why.
 */
class StampTokensTest {

    private static Map<String, String> tokens(String... lines) {
        return KeyAudit.lines("", List.of(lines));
    }

    private static final String SRC = "src:clients/cli/src/test/java/a/FooTest.java:" + "ab".repeat(32);
    private static final String CP = "cp:store:repos/central/g/a/1/a-1.jar:" + "cd".repeat(32);
    private static final String CLASSES = "cp:lib/target/classes:" + "ce".repeat(32);
    private static final String ORDER = "cp-order:" + "cf".repeat(32);

    @Test
    void a_new_jk_is_named_before_the_inputs_it_moved_with() {
        var was = tokens(SRC, CP, "x:jk:0.52.0-SNAPSHOT#8+aaaaaaaaaaaa", "x:compile-test:" + "01".repeat(32));
        var now = tokens(SRC, CP, "x:jk:0.52.0-SNAPSHOT#8+bbbbbbbbbbbb", "x:compile-test:" + "02".repeat(32));
        assertThat(StampTokens.reason(was, now)).isEqualTo("jk changed (suite code)");
    }

    /**
     * A version, a salt and the suite code each move the jk token alone, and in jk's own tree each
     * once read as the module under test changing: the reason names the part that moved.
     */
    @Test
    void a_new_jk_names_which_part_of_it_moved() {
        assertThat(StampTokens.jkChanged("x:jk:0.52.0#8+aaaaaaaaaaaa", "x:jk:0.53.0#8+aaaaaaaaaaaa"))
                .isEqualTo("jk changed (version)");
        assertThat(StampTokens.jkChanged("x:jk:0.52.0#8+aaaaaaaaaaaa", "x:jk:0.52.0#9+aaaaaaaaaaaa"))
                .isEqualTo("jk changed (key format)");
        assertThat(StampTokens.jkChanged("x:jk:0.52.0#8+aaaaaaaaaaaa", "x:jk:0.52.0#8+bbbbbbbbbbbb"))
                .isEqualTo("jk changed (suite code)");
        assertThat(StampTokens.jkChanged("x:jk:0.52.0#8+aaaaaaaaaaaa", "x:jk:0.53.0#9+bbbbbbbbbbbb"))
                .isEqualTo("jk changed (version, key format, suite code)");
        assertThat(StampTokens.jkChanged("x:jk:pinned-by-a-test", "x:jk:0.52.0#8+aaaaaaaaaaaa"))
                .isEqualTo(StampTokens.JK_CHANGED);
        var was = tokens(SRC, "x:jk:0.52.0-SNAPSHOT#8+aaaaaaaaaaaa");
        assertThat(StampTokens.reason(was, tokens(SRC, "x:jk:0.53.0-SNAPSHOT#8+aaaaaaaaaaaa")))
                .isEqualTo("jk changed (version)");
        assertThat(StampTokens.reason(was, tokens(SRC, "x:jk:0.52.0-SNAPSHOT#9+aaaaaaaaaaaa")))
                .isEqualTo("jk changed (key format)");
    }

    @Test
    void an_input_that_moved_is_named_with_how_many_more_did() {
        var was = tokens(SRC, CP, "x:read:bench/agent-loop/README.md=" + "11".repeat(32));
        var now = tokens(
                "src:clients/cli/src/test/java/a/FooTest.java:" + "ef".repeat(32),
                CP,
                "x:read:bench/agent-loop/README.md=" + "22".repeat(32));
        assertThat(StampTokens.reason(was, now)).isEqualTo("test source a/FooTest.java changed (+1 more)");
        assertThat(StampTokens.reason(
                        tokens(SRC, CP), tokens(SRC, "cp:store:repos/central/g/a/1/a-1.jar:" + "99".repeat(32))))
                .isEqualTo("runtime classpath entry a-1.jar changed");
        assertThat(StampTokens.describe("x:read:bench/agent-loop/README.md"))
                .isEqualTo("read file bench/agent-loop/README.md changed");
        assertThat(StampTokens.describe("x:worker:jk.test-runner.plugin.jar"))
                .isEqualTo("worker jk.test-runner.plugin.jar changed");
    }

    @Test
    void a_runtime_classpath_entry_or_its_order_is_named() {
        assertThat(StampTokens.reason(
                        tokens(SRC, CP, CLASSES, ORDER),
                        tokens(SRC, CP, "cp:lib/target/classes:" + "99".repeat(32), ORDER)))
                .isEqualTo("runtime classpath entry lib/target/classes changed");
        assertThat(StampTokens.reason(
                        tokens(SRC, CP, CLASSES, ORDER), tokens(SRC, CLASSES, CP, "cp-order:" + "98".repeat(32))))
                .isEqualTo("runtime classpath order changed");
    }

    /**
     * A file a suite was found to read keys the whole selection: jk-core's repo-scanning tests re-ran
     * 273 classes for an assertion edited in jk-resolver, read as "ExclusionLedgerTest.java changed".
     * The reason names the file by its whole path and the suites it keys.
     */
    @Test
    void a_read_file_is_named_whole_with_the_suites_it_keys() {
        String suites = "x:suites-run:test,integration";
        var was = tokens(
                SRC, CP, suites, "x:read:server/resolver/src/test/java/a/ExclusionLedgerTest.java=" + "11".repeat(32));
        var now = tokens(
                SRC, CP, suites, "x:read:server/resolver/src/test/java/a/ExclusionLedgerTest.java=" + "22".repeat(32));
        assertThat(StampTokens.reason(was, now))
                .isEqualTo("read file server/resolver/src/test/java/a/ExclusionLedgerTest.java changed"
                        + " · keys suites test,integration");
    }

    @Test
    void the_same_tokens_give_no_reason() {
        assertThat(StampTokens.reason(tokens(SRC, CP), tokens(SRC, CP))).isNull();
    }

    @Test
    void a_forecast_whose_compile_is_dirty_still_names_a_new_jk(@TempDir Path dir) {
        TestSelection sel = TestSelection.DEFAULT;
        assertThat(StampTokens.jkMoved(dir, sel, "0.52.0-SNAPSHOT#8+aaaaaaaaaaaa", "cccccccccccc"))
                .isNull();
        StampTokens.keep(dir, sel, "k", () -> {
            KeyAudit.tap("x:jk:0.52.0-SNAPSHOT#8+aaaaaaaaaaaa");
            return "k";
        });
        assertThat(StampTokens.jkMoved(dir, sel, "0.52.0-SNAPSHOT#8+aaaaaaaaaaaa", "cccccccccccc"))
                .isNull();
        assertThat(StampTokens.jkMoved(dir, sel, "0.52.0-SNAPSHOT#8+bbbbbbbbbbbb", "cccccccccccc"))
                .isEqualTo("jk changed (suite code)");
        assertThat(StampTokens.jkMoved(dir, sel, "0.53.0-SNAPSHOT#8+aaaaaaaaaaaa", "cccccccccccc"))
                .isEqualTo("jk changed (version)");
    }

    @Test
    void moved_judging_code_is_named_as_the_test_orchestrator(@TempDir Path dir) {
        TestSelection sel = TestSelection.DEFAULT;
        StampTokens.keep(dir, sel, "k", () -> {
            KeyAudit.tap("x:jk:0.52.0#8+aaaaaaaaaaaa");
            KeyAudit.tap("x:orchestrator:cccccccccccc");
            return "k";
        });
        assertThat(StampTokens.jkMoved(dir, sel, "0.52.0#8+aaaaaaaaaaaa", "cccccccccccc"))
                .isNull();
        assertThat(StampTokens.jkMoved(dir, sel, "0.52.0#8+aaaaaaaaaaaa", "dddddddddddd"))
                .isEqualTo("jk changed (test orchestrator)");
        assertThat(StampTokens.jkMoved(dir, sel, "0.52.0#8+bbbbbbbbbbbb", "dddddddddddd"))
                .isEqualTo("jk changed (suite code, test orchestrator)");
        assertThat(StampTokens.reason(
                        Map.of("x:jk:0.52.0#8+aaaaaaaaaaaa", "", "x:orchestrator:cccccccccccc", ""),
                        Map.of("x:jk:0.52.0#8+aaaaaaaaaaaa", "", "x:orchestrator:dddddddddddd", "")))
                .isEqualTo("jk changed (test orchestrator)");
    }

    /**
     * A forecast that runs a suite says why, also when no input of its last green run moved: jk's
     * own tree forecast jk-engine's 4,862 tests with no reason after a green gate. With no record
     * it says there is none; with the same tokens under a key that misses it says the key moved
     * with nothing named, which is what to report.
     */
    @Test
    void a_miss_always_names_a_reason(@TempDir Path dir) {
        TestSelection sel = TestSelection.DEFAULT;
        KeyAudit.KeySource source = () -> {
            KeyAudit.tap(SRC);
            return "k";
        };
        assertThat(StampTokens.reason(dir, sel, source)).isEqualTo(StampTokens.NO_GREEN_RUN);
        StampTokens.keep(dir, sel, "k", source);
        assertThat(StampTokens.reason(dir, sel, source)).isEqualTo(StampTokens.KEY_MOVED_UNNAMED);
        assertThat(StampTokens.reason(dir, sel, () -> {
                    throw new IOException("unreadable");
                }))
                .isEqualTo(StampTokens.KEY_UNREAD);
    }

    @Test
    void a_variable_a_suite_read_is_named_never_its_value() {
        Map<String, String> was = Map.of("x:env-read:MUST_BE_TRUE", "1111111111111111111111111111111111111111");
        Map<String, String> now = Map.of("x:env-read:MUST_BE_TRUE", "2222222222222222222222222222222222222222");
        assertThat(StampTokens.reason(was, now)).isEqualTo("MUST_BE_TRUE changed");
        assertThat(StampTokens.reason(Map.of("x:env-names", "a"), Map.of("x:env-names", "b")))
                .isEqualTo("the environment's variable names changed");
        assertThat(StampTokens.reason(Map.of("x:env-all", "a"), Map.of("x:env-all", "b")))
                .isEqualTo("the environment changed (a test starts programs)");
    }

    /** A variable newly set, newly unset, and the names a listing saw: each named, with what it did. */
    @Test
    void a_variable_set_or_unset_and_a_name_that_came_or_went_are_named() {
        String d1 = "1".repeat(64);
        String d2 = "2".repeat(64);
        assertThat(StampTokens.reason(Map.of("x:env-read:FOO", "unset"), Map.of("x:env-read:FOO", d1)))
                .isEqualTo("FOO set");
        assertThat(StampTokens.reason(Map.of("x:env-read:FOO", d1), Map.of("x:env-read:FOO", "unset")))
                .isEqualTo("FOO unset");
        Map<String, String> was = Map.of("x:env-names", d1, "env-name:MY_APP_FOO", "", "env-name:GONE", "");
        Map<String, String> now = Map.of("x:env-names", d2, "env-name:MY_APP_FOO", "", "env-name:MY_APP_BAR", "");
        assertThat(StampTokens.reason(was, now)).isEqualTo("GONE unset, MY_APP_BAR set");
        assertThat(StampTokens.reason(
                        Map.of("x:env-all", d1, "env-value:A", d1, "env-value:B", d1),
                        Map.of("x:env-all", d2, "env-value:A", d2, "env-value:B", d1, "env-value:C", d1)))
                .isEqualTo("A changed, C set");
        Map<String, String> many = Map.of(
                "x:env-names",
                d2,
                "env-name:A",
                "",
                "env-name:B",
                "",
                "env-name:C",
                "",
                "env-name:D",
                "",
                "env-name:E",
                "");
        assertThat(StampTokens.reason(Map.of("x:env-names", d1), many))
                .isEqualTo("A set, B set, C set and 2 more variables");
        assertThat(StampTokens.reason(Map.of("x:env-read:FOO", d1, "cp", d1), Map.of("x:env-read:FOO", d2, "cp", d2)))
                .as("another input moved beside it")
                .isEqualTo("FOO changed (+1 more)");
    }
}
