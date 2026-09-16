// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageToolsCountersTest {

    @Test
    void reads_the_report_level_line_and_branch_counters(@TempDir Path dir) throws Exception {
        Path xml = dir.resolve("jacoco.xml");
        Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="g:lib">
                  <package name="com/example">
                    <counter type="LINE" missed="99" covered="99"/>
                  </package>
                  <counter type="INSTRUCTION" missed="10" covered="90"/>
                  <counter type="BRANCH" missed="4" covered="6"/>
                  <counter type="LINE" missed="30" covered="120"/>
                </report>
                """);

        CoverageTools.Counters c = CoverageTools.counters(xml);

        assertThat(c).isEqualTo(new CoverageTools.Counters(120, 30, 6, 4));
    }

    @Test
    void a_report_without_branches_reads_zero_of_zero(@TempDir Path dir) throws Exception {
        Path xml = dir.resolve("jacoco.xml");
        Files.writeString(xml, """
                <report name="g:res"><counter type="LINE" missed="0" covered="2"/></report>
                """);
        assertThat(CoverageTools.counters(xml)).isEqualTo(new CoverageTools.Counters(2, 0, 0, 0));
    }
}
