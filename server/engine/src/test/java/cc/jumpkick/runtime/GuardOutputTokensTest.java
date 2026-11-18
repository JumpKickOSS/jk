// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The output lane's key covers the coverage and lint reports its measures read, by what they read. */
class GuardOutputTokensTest {

    private static final String CHECKSTYLE = "core/target/plugin/lint-checkstyle/lint/checkstyle/checkstyle.xml";
    private static final String NOHTTP =
            "core/target/plugin/lint-checkstyle-nohttp/lint/checkstyle-nohttp/checkstyle.xml";
    private static final String PMD = "core/target/plugin/lint-pmd/lint/pmd/pmd.xml";
    private static final String JACOCO = "core/target/site/jacoco/jacoco.xml";

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void scaffold(Path root, String coreExtra) throws Exception {
        write(root.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "1.0.0"
                [workspace]
                modules = ["core"]
                """);
        write(root.resolve("core/jk.toml"), """
                group = "com.acme"
                name = "core"
                version = "1.0.0"
                """ + coreExtra);
        write(root.resolve("core/target/core-1.0.0.jar"), "jar\n");
    }

    private static List<String> tokens(Path root) throws Exception {
        return GuardKeys.outputTokens(root, List.of(root.resolve("core")), null);
    }

    /** A Checkstyle report as the tool writes it: each file by its absolute path. */
    private static String checkstyle(Path root, int findings) {
        Path source = root.resolve("core/src/main/java/A.java").toAbsolutePath();
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>\n<checkstyle version=\"10.21\">\n");
        xml.append("<file name=\"").append(source).append("\">\n");
        for (int i = 0; i < findings; i++) {
            xml.append("<error line=\"").append(i + 1).append("\" severity=\"warning\" message=\"m\"/>\n");
        }
        return xml.append("</file>\n</checkstyle>\n").toString();
    }

    /** A PMD report as the tool writes it: the run's timestamp and each file by its absolute path. */
    private static String pmd(Path root, String timestamp, int findings) {
        Path source = root.resolve("core/src/main/java/A.java").toAbsolutePath();
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>\n<pmd version=\"7.9.0\" timestamp=\"")
                .append(timestamp)
                .append("\">\n<file name=\"")
                .append(source)
                .append("\">\n");
        for (int i = 0; i < findings; i++) {
            xml.append("<violation beginline=\"")
                    .append(i + 1)
                    .append("\" rule=\"UnusedLocalVariable\">m</violation>\n");
        }
        return xml.append("</file>\n</pmd>\n").toString();
    }

    @Test
    void the_same_findings_under_two_checkouts_key_alike_and_a_new_finding_moves_the_key(
            @TempDir Path a, @TempDir Path b) throws Exception {
        for (Path root : List.of(a, b)) scaffold(root, "");
        write(a.resolve(CHECKSTYLE), checkstyle(a, 2));
        write(a.resolve(PMD), pmd(a, "2026-11-18T10:00:00.000", 1));
        write(b.resolve(CHECKSTYLE), checkstyle(b, 2));
        write(b.resolve(PMD), pmd(b, "2026-11-18T11:30:00.000", 1));
        assertThat(Files.readString(a.resolve(CHECKSTYLE))).isNotEqualTo(Files.readString(b.resolve(CHECKSTYLE)));
        assertThat(tokens(a)).isEqualTo(tokens(b));

        List<String> before = tokens(b);
        write(b.resolve(PMD), pmd(b, "2026-11-18T12:00:00.000", 1));
        assertThat(tokens(b)).as("a rerun with the same findings").isEqualTo(before);

        write(b.resolve(CHECKSTYLE), checkstyle(b, 3));
        assertThat(tokens(b)).isNotEqualTo(before);
    }

    @Test
    void a_modules_first_report_moves_the_key(@TempDir Path root) throws Exception {
        scaffold(root, "");
        List<String> none = tokens(root);
        assertThat(none).contains("out:" + CHECKSTYLE + ":absent", "out:" + PMD + ":absent");
        write(root.resolve(PMD), pmd(root, "2026-11-18T10:00:00.000", 0));
        assertThat(tokens(root)).contains("out:" + PMD + ":findings=0").isNotEqualTo(none);
    }

    @Test
    void a_named_checkstyle_runs_report_is_keyed_beside_the_tables_own(@TempDir Path root) throws Exception {
        scaffold(root, """
                [lint]
                checkstyle = "config/checkstyle.xml"

                [lint.nohttp]
                checkstyle = "config/nohttp-checkstyle.xml"
                """);
        assertThat(tokens(root)).contains("out:" + NOHTTP + ":absent");
        write(root.resolve(NOHTTP), checkstyle(root, 1));
        assertThat(tokens(root)).contains("out:" + NOHTTP + ":findings=1");
    }

    @Test
    void a_report_that_does_not_parse_is_keyed_as_unreadable(@TempDir Path root) throws Exception {
        scaffold(root, "");
        write(root.resolve(PMD), "<pmd");
        write(root.resolve(JACOCO), "<report");
        assertThat(tokens(root)).contains("out:" + PMD + ":unreadable", "out:" + JACOCO + ":unreadable");
    }

    /** A JaCoCo report as the tool writes it: a session per test JVM, by id and times, then the counters. */
    private static String jacoco(String session, long start, int linesMissed) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">"
                + "<report name=\"core\"><sessioninfo id=\"" + session + "\" start=\"" + start
                + "\" dump=\"" + (start + 900) + "\"/>"
                + "<package name=\"a\"><counter type=\"LINE\" missed=\"9\" covered=\"9\"/></package>"
                + "<counter type=\"BRANCH\" missed=\"2\" covered=\"6\"/>"
                + "<counter type=\"LINE\" missed=\"" + linesMissed + "\" covered=\"30\"/></report>";
    }

    @Test
    void a_coverage_report_keys_its_counters_not_its_sessions(@TempDir Path a, @TempDir Path b) throws Exception {
        for (Path root : List.of(a, b)) scaffold(root, "");
        assertThat(tokens(a)).contains("out:" + JACOCO + ":absent");
        write(a.resolve(JACOCO), jacoco("hostA-1a2b", 1_763_400_000_000L, 4));
        write(b.resolve(JACOCO), jacoco("hostB-9f8e", 1_763_500_000_000L, 4));
        assertThat(tokens(a)).contains("out:" + JACOCO + ":counters={BRANCH=2/6, LINE=4/30}");
        assertThat(tokens(a)).isEqualTo(tokens(b));

        List<String> before = tokens(b);
        write(b.resolve(JACOCO), jacoco("hostB-7c6d", 1_763_600_000_000L, 4));
        assertThat(tokens(b)).as("a rerun measuring the same coverage").isEqualTo(before);

        write(b.resolve(JACOCO), jacoco("hostB-7c6d", 1_763_600_000_000L, 5));
        assertThat(tokens(b)).isNotEqualTo(before);
    }

    @Test
    void a_named_coverage_report_is_keyed_where_guards_says(@TempDir Path root) throws Exception {
        scaffold(root, "");
        write(root.resolve("build/coverage.xml"), jacoco("s", 1L, 1));
        assertThat(GuardKeys.outputTokens(root, List.of(root.resolve("core")), "build/coverage.xml"))
                .contains("out:build/coverage.xml:counters={BRANCH=2/6, LINE=1/30}")
                .noneMatch(t -> t.startsWith("out:" + JACOCO));
    }
}
