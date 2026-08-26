// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundtrips_a_fully_populated_record() {
        BuildRecord original = new BuildRecord(
                "20260710T143022417-3f9a",
                412,
                2,
                "build",
                "/proj \"quoted\"",
                "com.example:app",
                null /* projectId */,
                1000,
                4000,
                3000,
                false,
                false,
                1,
                "9.9-test",
                new BuildRecord.Tests(42, 40, 1, 1),
                List.of(new BuildRecord.Module(
                        "com.example:app",
                        "/proj",
                        false,
                        1,
                        3000,
                        List.of(
                                new BuildRecord.Task("compile", "compile", "SUCCESS", 800),
                                new BuildRecord.Task("test", "test", "FAIL", 1200)))),
                List.of(
                        new BuildRecord.Task("compile", "compile", "SUCCESS", 800),
                        new BuildRecord.Task("test", "test", "FAIL", 1200)),
                List.of(new BuildRecord.Diag(
                        "error",
                        "/proj",
                        "test",
                        "JUNIT",
                        "expected 1 but was 2\nline two",
                        "com.example.AppTest#adds",
                        "org.opentest4j.AssertionFailedError")),
                "web",
                "abc1234",
                new BuildRecord.CacheBenefit(9000, 6000, 3, 4),
                false,
                new BuildRecord.Io(1_024, 8_388_608, 2_048, 4_096),
                42L);

        BuildRecord back = Json.read(Json.write(original));

        assertThat(back.id()).isEqualTo(original.id());
        assertThat(back.buildNumber()).isEqualTo(412);
        assertThat(back.trigger()).isEqualTo("web");
        assertThat(back.commit()).isEqualTo("abc1234");
        assertThat(back.kind()).isEqualTo("build");
        assertThat(back.dir()).isEqualTo("/proj \"quoted\"");
        assertThat(back.coord()).isEqualTo("com.example:app");
        assertThat(back.finishedAt()).isEqualTo(4000);
        assertThat(back.success()).isFalse();
        assertThat(back.exitCode()).isEqualTo(1);
        assertThat(back.tests().total()).isEqualTo(42);
        assertThat(back.tests().failed()).isEqualTo(1);
        assertThat(back.modules()).hasSize(1);
        assertThat(back.modules().get(0).coord()).isEqualTo("com.example:app");
        assertThat(back.modules().get(0).steps())
                .extracting(BuildRecord.Task::name)
                .containsExactly("compile", "test");
        assertThat(back.steps()).extracting(BuildRecord.Task::status).containsExactly("SUCCESS", "FAIL");
        assertThat(back.steps()).extracting(BuildRecord.Task::stage).containsExactly("compile", "test");
        assertThat(back.diagnostics()).hasSize(1);
        assertThat(back.diagnostics().get(0).dir()).isEqualTo("/proj");
        assertThat(back.diagnostics().get(0).message()).isEqualTo("expected 1 but was 2\nline two");
        assertThat(back.diagnostics().get(0).exceptionClass()).isEqualTo("org.opentest4j.AssertionFailedError");
        assertThat(back.diagnostics().get(0).col()).isZero();
        assertThat(back.benefit()).isNotNull();
        assertThat(back.benefit().estimatedUncachedMillis()).isEqualTo(9000);
        assertThat(back.benefit().savedMillis()).isEqualTo(6000);
        assertThat(back.benefit().coveredSkips()).isEqualTo(3);
        assertThat(back.benefit().totalSkips()).isEqualTo(4);
        assertThat(back.io()).isNotNull();
        assertThat(back.io().remoteUp()).isEqualTo(1_024);
        assertThat(back.io().remoteDown()).isEqualTo(8_388_608);
        assertThat(back.io().localUp()).isEqualTo(2_048);
        assertThat(back.io().localDown()).isEqualTo(4_096);
        assertThat(back.requestId()).isEqualTo(42L);
    }

    @Test
    void roundtrips_a_minimal_record_with_null_coord_and_no_tests() {
        BuildRecord original = new BuildRecord(
                "20260710T143022417-0000",
                0,
                1,
                "test",
                "/p",
                null,
                null /* projectId */,
                0,
                5,
                5,
                true,
                true,
                0,
                "9.9",
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                false,
                null,
                0L);
        BuildRecord back = Json.read(Json.write(original));
        assertThat(back.coord()).isNull();
        assertThat(back.tests()).isNull();
        assertThat(back.cancelled()).isTrue();
        assertThat(back.modules()).isEmpty();
        assertThat(back.steps()).isEmpty();
        assertThat(back.diagnostics()).isEmpty();
        assertThat(back.benefit()).isNull();
        assertThat(back.io()).isNull();
    }

    @Test
    void reads_a_record_without_the_additive_benefit_and_io_fields() {
        // No "benefit"/"io" keys: both read null, and every other field still parses. The task
        // rows are populated on purpose — an empty array would enter readSteps' loop zero times
        // and assert nothing about how a task row is decoded.
        String raw = "{\"id\":\"old-1\",\"buildNumber\":7,\"schema\":2,\"kind\":\"build\","
                + "\"dir\":\"/p\",\"coord\":\"g:a\",\"startedAt\":0,\"finishedAt\":10,\"millis\":10,"
                + "\"success\":true,\"cancelled\":false,\"exitCode\":0,\"jkVersion\":\"9\","
                + "\"tests\":null,"
                + "\"modules\":[{\"coord\":\"g:a\",\"dir\":\"/p\",\"success\":true,\"exitCode\":0,"
                + "\"millis\":10,\"tasks\":[{\"name\":\"jar\",\"stage\":\"package\",\"status\":\"SUCCESS\","
                + "\"millis\":4}]}],"
                + "\"tasks\":[{\"name\":\"compile-java\",\"stage\":\"compile\",\"status\":\"SUCCESS\","
                + "\"millis\":6},{\"name\":\"run-tests\",\"stage\":\"test\",\"status\":\"FAIL\"}],"
                + "\"diagnostics\":[],"
                + "\"trigger\":\"cli\",\"commit\":\"deadbee\"}";
        BuildRecord back = Json.read(raw);
        assertThat(back.id()).isEqualTo("old-1");
        assertThat(back.success()).isTrue();
        assertThat(back.benefit()).isNull();
        assertThat(back.io()).isNull();
        assertThat(back.steps())
                .extracting(BuildRecord.Task::name, BuildRecord.Task::stage, BuildRecord.Task::status)
                .containsExactly(tuple("compile-java", "compile", "SUCCESS"), tuple("run-tests", "test", "FAIL"));
        // Absent millis is unknown (-1), never 0 — 0 is the true-no-op the dashboard renders dashed.
        assertThat(back.steps()).extracting(BuildRecord.Task::millis).containsExactly(6L, -1L);
        assertThat(back.modules()).hasSize(1);
        assertThat(back.modules().get(0).steps())
                .extracting(BuildRecord.Task::name, BuildRecord.Task::stage)
                .containsExactly(tuple("jar", "package"));
    }

    @Test
    void writes_each_diagnostic_field_under_exactly_one_name_and_reads_it_back() {
        BuildRecord.Diag diag = new BuildRecord.Diag(
                "error",
                "/proj",
                "run-tests",
                "test-failure",
                "expected 1 but was 2",
                "com.example.AppTest#adds",
                "org.opentest4j.AssertionFailedError",
                "com.example:app",
                "junit",
                "com.example.AppTest",
                "adds()",
                "at com.example.AppTest.adds(AppTest.java:12)");
        BuildRecord original = record(List.of(diag));

        String json = Json.write(original);

        assertThat(countOf(json, "\"testClass\"")).isOne();
        assertThat(json).doesNotContain("\"class\"").doesNotContain("\"throwable\"");
        assertThat(countOf(json, "\"task\"")).isOne();
        assertThat(countOf(json, "\"stack\"")).isOne();
        assertThat(countOf(json, "\"exceptionClass\"")).isOne();

        BuildRecord.Diag back = Json.read(json).diagnostics().get(0);
        assertThat(back).isEqualTo(diag);
    }

    private static BuildRecord record(List<BuildRecord.Diag> diagnostics) {
        return new BuildRecord(
                "20260710T143022417-abcd",
                1,
                BuildRecord.SCHEMA,
                "build",
                "/proj",
                "com.example:app",
                null,
                0,
                10,
                10,
                false,
                false,
                1,
                "9.9",
                null,
                List.of(),
                List.of(new BuildRecord.Task("run-tests", "test", "FAIL", 10)),
                diagnostics,
                "cli",
                "abc1234",
                null,
                false,
                null,
                0L);
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}
