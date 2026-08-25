// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import org.junit.jupiter.api.Test;

/**
 * One spelling for the failing test's class name on diagnostic lines:
 * {@link EngineProtocol#TEST_CLASS_FIELD}. The wire shipped it twice per line — {@code testClass}
 * then a {@code class} alias, unconditionally paired — and every reader grew a fallback ladder.
 * These assertions read the wire text, not a round trip, so a re-grown alias cannot hide behind a
 * decoder that tolerates it.
 */
class DiagnosticClassSpellingTest {

    @Test
    void the_field_is_the_journals_persisted_spelling() {
        assertThat(EngineProtocol.TEST_CLASS_FIELD).isEqualTo("testClass");
    }

    @Test
    void an_enriched_error_line_names_the_class_once_and_never_as_class() {
        String line = ProtoEvents.errorLine(
                "/w",
                "run-tests",
                "test-failure",
                "boom",
                "g:core",
                "junit-jupiter",
                "cc.jumpkick.FooTest",
                "bar()",
                "java.lang.AssertionError",
                "at cc.jumpkick.FooTest.bar(FooTest.java:9)");

        assertThat(Jsonl.topStr(line, EngineProtocol.TEST_CLASS_FIELD)).isEqualTo("cc.jumpkick.FooTest");
        assertThat(line).doesNotContain("\"class\":");
    }

    @Test
    void a_plan_diagnostic_uses_the_same_single_spelling() {
        String line = ProtoEvents.planDiagnostic(
                "/w",
                "run-tests",
                "test-failure",
                "boom",
                "g:core",
                "junit-jupiter",
                "cc.jumpkick.FooTest",
                "bar()",
                "java.lang.AssertionError",
                "");

        assertThat(Jsonl.topStr(line, EngineProtocol.TEST_CLASS_FIELD)).isEqualTo("cc.jumpkick.FooTest");
        assertThat(line).doesNotContain("\"class\":");
    }
}
