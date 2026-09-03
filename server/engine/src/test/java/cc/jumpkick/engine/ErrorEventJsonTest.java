// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The SSE {@code error} event body spells the failing test's class
 * {@link EngineProtocol#TEST_CLASS_FIELD} — the journal's persisted spelling — never the retired
 * {@code class} alias the dashboard once had to ladder over.
 */
class ErrorEventJsonTest {

    @Test
    void a_test_failure_event_carries_testClass_and_no_class_alias() {
        var d = new BuildPlanResult.Diagnostic(
                "run-tests",
                "test-failure",
                "boom",
                new TestFailureInfo(
                        "g:core",
                        "junit-jupiter",
                        "cc.jumpkick.FooTest",
                        "bar()",
                        "java.lang.AssertionError",
                        "boom",
                        "at cc.jumpkick.FooTest.bar(FooTest.java:9)",
                        2,
                        "src/test/java/cc/jumpkick/FooTest.java",
                        9,
                        6,
                        List.of("int a = 1;", "assertEquals(1, 2);")));

        String json = SsePublisher.errorEventJson(7L, "/w", d).toString();

        assertThat(Jsonl.topStr(json, EngineProtocol.TEST_CLASS_FIELD)).isEqualTo("cc.jumpkick.FooTest");
        assertThat(json).doesNotContain("\"class\":");
        assertThat(Jsonl.str(json, "task")).isEqualTo("run-tests");
        assertThat(Jsonl.str(json, "method")).isEqualTo("bar()");
        assertThat(Jsonl.intValue(json, "worker", 0)).isEqualTo(2);
    }
}
