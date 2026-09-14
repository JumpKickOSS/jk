// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The XML report names its files from whatever the engine's unique ids carry: Jupiter's
 * {@code [class:…]}, Spock's {@code [spec:…]}, Vintage's {@code [runner:…]}. A raw unique id is
 * not a file name — its {@code /} is a directory separator and its {@code :} is illegal on Windows
 * — and one class the report cannot write must never take the rest of the suite's files with it.
 */
class XmlTestReportTest {

    @TempDir
    Path dir;

    @Test
    void spock_vintage_and_jupiter_ids_each_land_in_a_file_named_for_their_class() throws Exception {
        var xml = new XmlTestReport();
        xml.recordFinished(
                "[engine:spock]/[spec:com.example.WidgetSpec]/[feature:$spock_feature_0_0]", "adds", 3, null);
        xml.recordFinished(
                "[engine:junit-vintage]/[runner:com.example.LegacyTest]/[test:works(com.example.LegacyTest)]",
                "works",
                2,
                null);
        xml.recordFinished("[engine:junit-jupiter]/[class:com.example.ModernTest]/[method:runs()]", "runs()", 1, null);

        xml.writeAll(dir);

        assertThat(dir.resolve("TEST-com.example.WidgetSpec.xml")).exists();
        assertThat(dir.resolve("TEST-com.example.LegacyTest.xml")).exists();
        assertThat(dir.resolve("TEST-com.example.ModernTest.xml")).exists();
        assertThat(Files.readString(dir.resolve("TEST-com.example.WidgetSpec.xml")))
                .contains("<testsuite name=\"com.example.WidgetSpec\"")
                .contains("classname=\"com.example.WidgetSpec\"");
    }

    @Test
    void an_id_with_no_class_like_segment_still_writes_one_portable_file_name() throws Exception {
        var xml = new XmlTestReport();
        xml.recordFinished(
                "[engine:cucumber]/[feature:classpath:features/login.feature]/[scenario:3]", "logs in", 1, null);
        xml.recordSkipped("[engine:junit-jupiter]/[class:com.example.Other]/[method:m()]", "m()", "disabled");

        xml.writeAll(dir);

        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.toList();
        }
        assertThat(files).hasSize(2);
        assertThat(files)
                .allSatisfy(f -> assertThat(f.getFileName().toString()).matches("TEST-[A-Za-z0-9._$-]+\\.xml"));
        assertThat(dir.resolve("TEST-com.example.Other.xml")).exists();
    }

    @Test
    void what_a_class_printed_while_it_ran_is_its_suite_s_system_out() throws Exception {
        var xml = new XmlTestReport();
        xml.recordOutput("com.example.Noisy", "jk dev stderr: app exited with 143");
        xml.recordOutput("com.example.Noisy", "event: session-finish exit=70");
        xml.recordFinished("[engine:junit-jupiter]/[class:com.example.Noisy]/[method:m()]", "m()", 1, null);
        xml.recordFinished("[engine:junit-jupiter]/[class:com.example.Quiet]/[method:q()]", "q()", 1, null);

        xml.writeAll(dir);

        assertThat(Files.readString(dir.resolve("TEST-com.example.Noisy.xml")))
                .contains("<system-out><![CDATA[jk dev stderr: app exited with 143\nevent: session-finish exit=70\n]]>"
                        + "</system-out>");
        assertThat(Files.readString(dir.resolve("TEST-com.example.Quiet.xml")))
                .contains("<system-out><![CDATA[]]></system-out>");
    }

    @Test
    void a_class_s_output_is_capped_and_the_cut_is_counted() {
        var xml = new XmlTestReport();
        String line = "x".repeat(1024);
        for (int i = 0; i < XmlTestReport.MAX_OUTPUT_CHARS / 1000; i++) xml.recordOutput("C", line);
        xml.recordOutput("C", "late");
        xml.recordOutput("C", "later");

        String out = xml.outputOf("C");
        assertThat(out.length()).isLessThanOrEqualTo(XmlTestReport.MAX_OUTPUT_CHARS + 64);
        assertThat(out).endsWith("more line(s))\n").doesNotContain("later");
    }

    @Test
    void class_names_come_from_the_segment_the_engine_uses() {
        assertThat(XmlTestReport.classNameFrom("[engine:junit-jupiter]/[class:a.B]/[nested-class:C]/[method:m()]"))
                .isEqualTo("a.B$C");
        assertThat(XmlTestReport.classNameFrom("[engine:spock]/[spec:a.BSpec]/[feature:f]"))
                .isEqualTo("a.BSpec");
        assertThat(XmlTestReport.classNameFrom("[engine:junit-vintage]/[runner:a.OldTest]/[test:t(a.OldTest)]"))
                .isEqualTo("a.OldTest");
        assertThat(XmlTestReport.classNameFrom("[engine:kotest]/[spec:a.KSpec]/[test:does a thing]"))
                .isEqualTo("a.KSpec");
        assertThat(XmlTestReport.classNameFrom("[engine:cucumber]/[feature:x.feature]/[scenario:1]"))
                .isEqualTo("x.feature");
    }
}
