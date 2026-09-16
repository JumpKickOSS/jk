// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The delta between two consecutive runs: diagnostics keyed on what they say and where, tests on
 * their verdict, files on their hash — each list an exact count with a bounded head.
 */
class JobDeltaTest {

    private static BuildRecord run(long number, boolean success, long millis, List<BuildRecord.Diag> diags) {
        return new BuildRecord(
                "r" + number,
                number,
                BuildRecord.SCHEMA,
                "build",
                "/ws",
                "g:a",
                "p",
                1_000 * number,
                1_000 * number + millis,
                millis,
                success,
                false,
                success ? 0 : 1,
                "9.9",
                null,
                List.of(),
                List.of(),
                diags,
                "mcp",
                "claude-code 3f9a",
                null,
                null,
                false,
                null,
                0L,
                null,
                List.of());
    }

    private static BuildRecord.Diag compileError(String file, int line, String message) {
        return new BuildRecord.Diag(
                "error",
                "",
                "compile-java",
                "compile",
                message,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                file,
                line,
                3,
                0,
                List.of(),
                0);
    }

    private static BuildRecord.Diag testFailure(String test, String message) {
        return new BuildRecord.Diag("error", "", "run-tests", "test", message, test, "AssertionError");
    }

    @Test
    void diagnostics_that_appeared_and_went_away_are_listed_with_project_relative_sites() {
        BuildRecord before = run(
                1,
                false,
                8_400,
                List.of(
                        compileError("/ws/src/main/java/Foo.java", 12, "cannot find symbol"),
                        testFailure("FooTest#adds()", "expected 4")));
        BuildRecord after = run(
                2,
                false,
                6_100,
                List.of(
                        compileError("/ws/src/main/java/Foo.java", 12, "cannot find symbol"),
                        compileError("/ws/src/main/java/Bar.java", 3, "';' expected")));

        JobDelta d = JobDelta.compute(before, after, null, null, null, null);

        assertThat(d.previousBuildNumber()).isEqualTo(1);
        assertThat(d.previousSuccess()).isFalse();
        assertThat(d.previousMillis()).isEqualTo(8_400);
        assertThat(d.appeared().count()).isEqualTo(1);
        assertThat(d.appeared().shown())
                .containsExactly("error · compile-java · src/main/java/Bar.java:3 · ';' expected");
        assertThat(d.gone().count()).isEqualTo(1);
        assertThat(d.gone().shown()).containsExactly("error · run-tests · FooTest#adds() · expected 4");
        assertThat(d.files())
                .as("no snapshot on either side: no file comparison")
                .isNull();
        assertThat(d.comparedTests()).isFalse();
        assertThat(d.quiet()).isFalse();
    }

    @Test
    void a_diagnostic_with_a_different_stack_but_the_same_site_and_message_is_the_same_diagnostic() {
        BuildRecord.Diag a = new BuildRecord.Diag(
                "error", "", "run-tests", "test", "boom", "T#x()", "E", "", "", "T", "x", "at A\n");
        BuildRecord.Diag b = new BuildRecord.Diag(
                "error", "", "run-tests", "test", "boom", "T#x()", "E", "", "", "T", "x", "at B\n");
        JobDelta d =
                JobDelta.compute(run(1, false, 1, List.of(a)), run(2, false, 1, List.of(b)), null, null, null, null);
        assertThat(d.appeared().count()).isZero();
        assertThat(d.gone().count()).isZero();
        assertThat(d.quiet()).isTrue();
    }

    @Test
    void tests_flip_four_ways_and_a_skip_is_present_without_a_verdict() {
        Map<String, Character> before = new TreeMap<>(Map.of(
                "FooTest#adds()", RunSnapshots.FAIL,
                "FooTest#subtracts()", RunSnapshots.PASS,
                "FooTest#divides()", RunSnapshots.PASS,
                "OldTest#gone()", RunSnapshots.PASS,
                "SlowTest#skipped()", RunSnapshots.SKIP));
        Map<String, Character> after = new TreeMap<>(Map.of(
                "FooTest#adds()", RunSnapshots.PASS,
                "FooTest#subtracts()", RunSnapshots.FAIL,
                "FooTest#divides()", RunSnapshots.PASS,
                "NewTest#fresh()", RunSnapshots.PASS,
                "SlowTest#skipped()", RunSnapshots.PASS));

        JobDelta d =
                JobDelta.compute(run(3, false, 1, List.of()), run(4, false, 1, List.of()), before, after, null, null);

        assertThat(d.comparedTests()).isTrue();
        assertThat(d.fixed().shown()).containsExactly("FooTest#adds()");
        assertThat(d.broke().shown()).containsExactly("FooTest#subtracts()");
        assertThat(d.added().shown()).containsExactly("NewTest#fresh()");
        assertThat(d.dropped().shown()).containsExactly("OldTest#gone()");
        assertThat(d.fixed().count()
                        + d.broke().count()
                        + d.added().count()
                        + d.dropped().count())
                .isEqualTo(4);
    }

    @Test
    void files_are_compared_by_hash_and_marked_new_or_gone() {
        Map<String, String> before = Map.of("src/A.java", "h1", "src/B.java", "h2", "jk.toml", "h3");
        Map<String, String> after = Map.of("src/A.java", "h1", "src/B.java", "h2x", "src/C.java", "h4");

        JobDelta d =
                JobDelta.compute(run(1, true, 1, List.of()), run(2, true, 1, List.of()), null, null, before, after);

        assertThat(d.files()).isNotNull();
        assertThat(d.files().count()).isEqualTo(3);
        assertThat(d.files().shown()).containsExactly("jk.toml (gone)", "src/B.java", "src/C.java (new)");
    }

    @Test
    void lists_keep_an_exact_count_beside_a_bounded_head() {
        Map<String, String> before = new TreeMap<>();
        Map<String, String> after = new TreeMap<>();
        for (int i = 0; i < 30; i++) {
            before.put("src/F" + i + ".java", "old");
            after.put("src/F" + i + ".java", "new");
        }
        JobDelta d =
                JobDelta.compute(run(1, true, 1, List.of()), run(2, true, 1, List.of()), null, null, before, after);
        assertThat(d.files().count()).isEqualTo(30);
        assertThat(d.files().shown()).hasSize(JobDelta.MAX_SHOWN);
        assertThat(d.files().more()).isEqualTo(30 - JobDelta.MAX_SHOWN);
    }

    @Test
    void the_delta_round_trips_through_record_json() {
        Map<String, Character> before = Map.of("T#a()", RunSnapshots.FAIL);
        Map<String, Character> after = Map.of("T#a()", RunSnapshots.PASS);
        BuildRecord previous = run(1, false, 900, List.of(testFailure("T#a()", "no")));
        BuildRecord current = run(2, true, 700, List.of());
        JobDelta d = JobDelta.compute(previous, current, before, after, Map.of("a", "1"), Map.of("a", "2"));

        BuildRecord back = Json.read(Json.write(current.withDelta(d)));

        assertThat(back.delta()).isEqualTo(d);
        assertThat(Json.read(Json.write(current)).delta()).isNull();
    }
}
