// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** {@code [test] exclude-src} globs match a source's path under its root. */
class TestSourceExcludesTest {

    private static final String PROJECT = """
            group = "com.ex"
            name = "csv"
            version = "1.0.0"

            [test]
            extra-src = ["src/test/java11"]
            exclude-src = ["**/*Benchmark*", "org/acme/perf/**"]
            """;

    @Test
    void a_glob_matches_under_the_suite_root_and_the_extra_root() {
        JkBuild project = JkBuildParser.parse(PROJECT);
        Path dir = Path.of("/work/csv");
        TestSourceExcludes excludes =
                Objects.requireNonNull(TestSourceExcludes.of(project, dir, false, List.of("test")));

        Path kept = dir.resolve("src/test/java/org/acme/CsvTest.java");
        Path kotlin = dir.resolve("src/test/kotlin/org/acme/CsvKtTest.kt");
        assertThat(excludes.keep(List.of(
                        kept,
                        dir.resolve("src/test/java/org/acme/CSVBenchmark.java"),
                        dir.resolve("src/test/java/CsvBenchmarkAtRoot.java"),
                        dir.resolve("src/test/java/org/acme/perf/Load.java"),
                        dir.resolve("src/test/java11/org/acme/perf/Load11.java"),
                        kotlin)))
                .containsExactly(kept, kotlin);
    }

    @Test
    void no_globs_is_no_filter() {
        JkBuild project = JkBuildParser.parse("""
                group = "com.ex"
                name = "csv"
                version = "1.0.0"
                """);
        assertThat(TestSourceExcludes.of(project, Path.of("/work/csv"), false, List.of()))
                .isNull();
    }
}
