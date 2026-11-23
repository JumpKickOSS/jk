// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.orchestrator.ResultAggregator;
import cc.jumpkick.orchestrator.SuiteReport;
import cc.jumpkick.orchestrator.TestProgressListener;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JUnit XML and the Markdown report keep a test's failure text and its printed lines masked of
 * the workspace's secrets, as the wire does.
 */
class ReportRedactionTest {

    private static final String CLASS_ID = "[engine:junit-jupiter]/[class:com.acme.ApiTest]";
    private static final String METHOD_ID = CLASS_ID + "/[method:calls()]";

    @AfterEach
    void forget() {
        ResolvedSecrets.clear();
    }

    @Test
    void a_failure_message_and_stack_quoting_a_filed_credential_reach_the_reports_masked(@TempDir Path reports)
            throws IOException {
        String secret = "tok-1234567890";
        ResolvedSecrets.recordFor(SessionContext.current().workingDir(), secret);
        XmlTestReport xml = new XmlTestReport();
        MarkdownTestReport md = new MarkdownTestReport();
        RunResults sink = new RunResults();
        ResultAggregator agg = new ResultAggregator(TestProgressListener.noop(), 0, SuiteReport.all(xml, md), "");

        agg.accept(started(CLASS_ID));
        agg.accept(started(METHOD_ID));
        agg.userOutput("printed " + secret);
        agg.accept("{\"event\":\"finished\",\"uniqueId\":\"" + METHOD_ID + "\",\"testEngine\":\"junit-jupiter\","
                + "\"testClass\":\"com.acme.ApiTest\",\"testMethod\":\"calls()\",\"type\":\"TEST\","
                + "\"status\":\"FAILED\",\"throwable\":{\"class\":\"java.lang.AssertionError\","
                + "\"message\":\"401 for Bearer " + secret + "\","
                + "\"stack\":\"java.lang.AssertionError: 401 for Bearer " + secret
                + "\\n\\tat com.acme.ApiTest.calls(ApiTest.java:9)\"}}");
        xml.writeAll(reports);
        Request.detached().with(RunResults.SLOT, sink).run(() -> md.publish(reports.toString(), "core", cls -> ""));

        String written = readAll(reports);
        assertThat(written)
                .as("the printed line is masked (the path that already redacts)")
                .contains("printed ***");
        assertThat(written).as("TEST-*.xml failure message and stack").doesNotContain(secret);
        List<MarkdownTestReport.Entry> entries = sink.takeTests().getFirst().entries();
        assertThat(entries).singleElement().satisfies(e -> {
            assertThat(e.failureMessage()).as("jk-results.md failure message").doesNotContain(secret);
            assertThat(e.failureStack()).as("jk-results.md failure stack").doesNotContain(secret);
        });
    }

    @Test
    void a_printed_dot_env_value_is_masked_in_the_xml_as_the_wire_masks_it(@TempDir Path project) throws Exception {
        String secret = "postgres://u:s3cr3tpass@localhost/db";
        Files.writeString(project.resolve(".env"), "DATABASE_URL=" + secret + "\n");
        Files.writeString(project.resolve("jk.toml"), "[project]\nname = \"demo\"\n");
        Path reports = Files.createDirectories(project.resolve("target/surefire-reports"));
        XmlTestReport xml = new XmlTestReport();

        SessionContext.where(Session.defaults().withWorkingDir(project), () -> {
            assertThat(BuildEnv.secretsFor(project).redact("url=" + secret))
                    .as("the wire's redactor masks a .env value by declaration")
                    .isEqualTo("url=***");
            ResultAggregator agg = new ResultAggregator(TestProgressListener.noop(), 0, SuiteReport.all(xml, null), "");
            agg.accept(started(CLASS_ID));
            agg.accept(started(METHOD_ID));
            agg.userOutput("url=" + secret);
            agg.accept("{\"event\":\"finished\",\"uniqueId\":\"" + METHOD_ID + "\",\"testEngine\":\"junit-jupiter\","
                    + "\"testClass\":\"com.acme.ApiTest\",\"testMethod\":\"calls()\",\"type\":\"TEST\","
                    + "\"status\":\"SUCCESSFUL\"}");
            agg.accept("{\"event\":\"finished\",\"uniqueId\":\"" + CLASS_ID + "\",\"type\":\"CONTAINER\","
                    + "\"status\":\"SUCCESSFUL\",\"testClass\":\"com.acme.ApiTest\"}");
            return null;
        });
        xml.writeAll(reports);

        String written = readAll(reports);
        assertThat(written).as("the line reached <system-out>").contains("url=");
        assertThat(written).as("TEST-*.xml <system-out>").doesNotContain("s3cr3tpass");
    }

    private static String started(String id) {
        return "{\"event\":\"started\",\"uniqueId\":\"" + id + "\",\"type\":\""
                + (id.contains("[method:") ? "TEST" : "CONTAINER") + "\"}";
    }

    private static String readAll(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
        }
    }
}
