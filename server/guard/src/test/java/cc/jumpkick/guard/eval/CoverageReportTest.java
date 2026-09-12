// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The report JaCoCo writes, DOCTYPE included, read for its whole-report counters. */
class CoverageReportTest {

    private static final String REPORT = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="g:a"><package name="a"><class name="a/A" sourcefilename="A.java">
            <counter type="LINE" missed="1" covered="1"/></class>
            <counter type="LINE" missed="1" covered="1"/></package>
            <counter type="INSTRUCTION" missed="10" covered="30"/>
            <counter type="LINE" missed="20" covered="60"/>
            <counter type="BRANCH" missed="0" covered="0"/>
            </report>
            """;

    @Test
    void the_report_level_counters_are_read_through_the_doctype(@TempDir Path tmp) throws Exception {
        Path xml = Files.writeString(tmp.resolve("jacoco.xml"), REPORT);
        assertThat(CoverageReport.percent(xml, "line")).isEqualTo(75.0);
        assertThat(CoverageReport.percent(xml, "branch"))
                .as("nothing to cover is fully covered")
                .isEqualTo(100.0);
        assertThat(CoverageReport.percent(xml, "method")).isNull();
    }
}
