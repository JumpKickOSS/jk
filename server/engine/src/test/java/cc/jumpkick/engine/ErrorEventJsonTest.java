// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SSE {@code error} event body spells the failing test's class
 * {@link EngineProtocol#TEST_CLASS_FIELD} — the journal's persisted spelling — never the retired
 * {@code class} alias, and carries a resolve's conflict, redacted, as {@code -O jsonl} does.
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

        assertThat(Jsonl.str(json, EngineProtocol.TEST_CLASS_FIELD)).isEqualTo("cc.jumpkick.FooTest");
        assertThat(json).doesNotContain("\"class\":");
        assertThat(Jsonl.str(json, "task")).isEqualTo("run-tests");
        assertThat(Jsonl.str(json, "method")).isEqualTo("bar()");
        assertThat(Jsonl.intValue(json, "worker", 0)).isEqualTo(2);
    }

    @Test
    void a_resolve_with_no_solution_carries_its_conflict_as_one_object() {
        String conflict = "{\"conclusion\":\"no solution\",\"facts\":[{\"kind\":\"no-versions\",\"package\":\"a:b\"}]}";

        String json =
                SsePublisher.errorEventJson(7L, "/w", resolveError(conflict)).toString();

        Map<?, ?> frame = (Map<?, ?>) requireNonNull(MiniJson.parse(json));
        Map<?, ?> carried = (Map<?, ?>) requireNonNull(frame.get("conflict"));
        assertThat(carried.get("conclusion")).isEqualTo("no solution");
        assertThat(carried.get("facts")).asInstanceOf(LIST).singleElement().satisfies(fact -> assertThat(
                        ((Map<?, ?>) fact).get("package"))
                .isEqualTo("a:b"));
    }

    @Test
    void a_secret_in_the_conflict_is_masked(@TempDir Path project) throws IOException {
        String secret = "sse-conflict-s3cret-token";
        Files.writeString(project.resolve(".env"), "TOKEN=" + secret + "\n");

        String json = SsePublisher.errorEventJson(
                        7L, project.toString(), resolveError("{\"conclusion\":\"401 for " + secret + "\"}"))
                .toString();

        assertThat(json).doesNotContain(secret).contains(SecretRedactor.MASK);
    }

    private static BuildPlanResult.Diagnostic resolveError(String conflict) {
        return new BuildPlanResult.Diagnostic(
                "resolve",
                "resolve",
                "no version of a:b satisfies 2.0",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                0,
                0,
                List.of(),
                0,
                "",
                "",
                conflict);
    }
}
