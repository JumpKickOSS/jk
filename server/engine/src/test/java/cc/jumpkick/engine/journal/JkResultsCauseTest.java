// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.MarkdownTestReport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A failed test whose trace is deeper than the clip still names its root cause, and a cause that
 * names a resource the test could not load ends with a hint naming the file.
 */
class JkResultsCauseTest {

    /** Spring Boot's context failure over a Spring Batch reader whose CSV is missing. */
    private static String batchStack() {
        StringBuilder sb = new StringBuilder();
        sb.append("java.lang.IllegalStateException: Failed to load ApplicationContext for [MergedContextConfiguration"
                + " testClass = com.example.BatchProcessingApplicationTests]\n");
        for (int i = 0; i < 30; i++) {
            sb.append("\tat org.springframework.test.context.Frame")
                    .append(i)
                    .append(".run(Frame.java:")
                    .append(i)
                    .append(")\n");
        }
        sb.append(
                "\tat com.example.BatchProcessingApplicationTests.contextLoads(BatchProcessingApplicationTests.java:14)\n");
        sb.append("Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name"
                + " 'step1': Invocation of init method failed\n");
        sb.append(
                "\tat org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1806)\n");
        sb.append("\t... 20 more\n");
        sb.append("Caused by: org.springframework.batch.item.ItemStreamException: Failed to initialize the reader\n");
        sb.append(
                "\tat org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader.open(AbstractItemCountingItemStreamItemReader.java:153)\n");
        sb.append("\t... 30 more\n");
        sb.append("Caused by: java.lang.IllegalStateException: Input resource must exist (reader is in 'strict' mode):"
                + " class path resource [sample-data.csv]\n");
        sb.append("\tat org.springframework.batch.item.file.FlatFileItemReader.doOpen(FlatFileItemReader.java:266)\n");
        sb.append("\t... 32 more\n");
        return sb.toString();
    }

    @Test
    void a_failed_test_names_the_root_cause_the_clip_dropped_and_the_missing_resource() {
        String stack = batchStack();
        MarkdownTestReport.Entry fail = new MarkdownTestReport.Entry(
                "com.example.BatchProcessingApplicationTests",
                "contextLoads()",
                120,
                "Failed to load ApplicationContext",
                stack,
                null);
        var run = new MarkdownTestReport.ModuleRun("/ws", "com.example:batch", List.of(fail));
        String md = JkResultsMarkdown.render(record(List.of()), null, null, List.of(run));

        assertThat(md)
                .contains("root cause: java.lang.IllegalStateException: Input resource must exist"
                        + " (reader is in 'strict' mode): class path resource [sample-data.csv]");
        assertThat(md)
                .contains(
                        "→ the test loads `sample-data.csv` and nothing on its classpath ships it:"
                                + " add the file under `src/test/resources/` (or `src/main/resources/`), or fix the path it loads.");
    }

    @Test
    void the_failures_section_carries_the_same_root_cause_and_hint() {
        BuildRecord.Diag diag = new BuildRecord.Diag(
                "error",
                "/ws",
                "run-tests",
                "test-failure",
                "Failed to load ApplicationContext",
                "",
                "java.lang.IllegalStateException",
                "com.example:batch",
                "junit",
                "com.example.BatchProcessingApplicationTests",
                "contextLoads",
                batchStack(),
                "",
                0,
                0,
                0,
                List.of(),
                0);
        String md = JkResultsMarkdown.render(record(List.of(diag)));
        assertThat(md).contains("### Tests");
        assertThat(md).contains("root cause: java.lang.IllegalStateException: Input resource must exist");
        assertThat(md).contains("→ the test loads `sample-data.csv`");
    }

    @Test
    void a_short_trace_whose_root_cause_is_visible_gets_no_second_copy() {
        String stack = "java.lang.IllegalStateException: boom\n"
                + "\tat com.example.FooTest.bar(FooTest.java:9)\n"
                + "Caused by: java.io.IOException: disk\n"
                + "\tat com.example.Foo.read(Foo.java:3)\n";
        MarkdownTestReport.Entry fail =
                new MarkdownTestReport.Entry("com.example.FooTest", "bar()", 1, "boom", stack, null);
        var run = new MarkdownTestReport.ModuleRun("/ws", "g:a", List.of(fail));
        String md = JkResultsMarkdown.render(record(List.of()), null, null, List.of(run));
        assertThat(md).doesNotContain("root cause:");
        assertThat(md).doesNotContain("→");
    }

    @Test
    void the_chain_is_read_outermost_first_and_the_root_is_its_last_header() {
        String stack = batchStack();
        assertThat(JkResultsCause.headers(stack))
                .hasSize(4)
                .first()
                .asString()
                .startsWith("java.lang.IllegalStateException: Failed to load ApplicationContext");
        assertThat(JkResultsCause.rootCause(stack))
                .startsWith("java.lang.IllegalStateException: Input resource must exist");
        assertThat(JkResultsCause.rootCause("java.lang.AssertionError: no\n\tat a.B.c(B.java:1)\n"))
                .isNull();
    }

    @Test
    void resource_descriptions_of_every_loader_name_the_file() {
        assertThat(JkResultsCause.missingResource(
                        "java.io.FileNotFoundException: class path resource [schema.sql] cannot be opened because it"
                                + " does not exist\n\tat org.springframework.core.io.ClassPathResource.getInputStream"))
                .isEqualTo("schema.sql");
        assertThat(JkResultsCause.missingResource("x\nCaused by: org.springframework.jdbc.datasource.init"
                        + ".CannotReadScriptException: Cannot read SQL script from class path resource [data.sql]"))
                .isEqualTo("data.sql");
        assertThat(JkResultsCause.missingResource(
                        "java.io.FileNotFoundException: /tmp/in/orders.csv (No such file or directory)\n\tat x"))
                .isEqualTo("/tmp/in/orders.csv");
        assertThat(JkResultsCause.missingResource("java.nio.file.NoSuchFileException: config/app.yaml\n"))
                .isEqualTo("config/app.yaml");
        assertThat(JkResultsCause.missingResource("java.lang.AssertionError: expected 1\n\tat a.B.c(B.java:1)"))
                .isNull();
    }

    @Test
    void an_absolute_path_gets_the_file_advice_and_a_script_statement_names_its_script() {
        assertThat(JkResultsCause.hint(
                        "java.io.FileNotFoundException: /tmp/in/orders.csv (No such file or directory)\n\tat x"))
                .isEqualTo(
                        "the test opens `/tmp/in/orders.csv` and the file does not exist: create it, or fix the path.");
        assertThat(
                        JkResultsCause.hint(
                                "java.lang.IllegalStateException: Failed to load ApplicationContext\n"
                                        + "Caused by: org.springframework.jdbc.datasource.init.ScriptStatementFailedException: Failed to"
                                        + " execute SQL script statement #2 of class path resource [schema.sql]: CREATE TABLE customer"))
                .isEqualTo("statement #2 of `schema.sql` failed — the script the test runs: fix the SQL, or the"
                        + " schema it assumes.");
        assertThat(JkResultsCause.hint("java.lang.AssertionError: expected 1")).isNull();
    }

    private static BuildRecord record(List<BuildRecord.Diag> diags) {
        List<BuildRecord.Task> steps = new ArrayList<>();
        steps.add(new BuildRecord.Task("run-tests", "test", "FAIL", 400, 0L));
        return new BuildRecord(
                "id",
                3,
                BuildRecord.SCHEMA,
                "test",
                "/ws",
                "com.example:batch",
                "pid",
                1_000,
                1_100,
                100,
                false,
                false,
                4,
                "9.9",
                new BuildRecord.Tests(1, 0, 1, 0),
                List.of(),
                steps,
                diags,
                "cli",
                null,
                null,
                null,
                false,
                null,
                0L);
    }
}
