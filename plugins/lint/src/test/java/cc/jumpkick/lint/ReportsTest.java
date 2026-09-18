// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each tool's report read back as findings: location, severity, rule id and message. */
class ReportsTest {

    @Test
    void checkstyle_errors_carry_the_check_name_as_the_rule(@TempDir Path tmp) throws Exception {
        Path report = Files.writeString(tmp.resolve("checkstyle.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <checkstyle version="14.1.0">
                <file name="/m/src/main/java/demo/Sample.java">
                <error line="12" column="9" severity="warning" message="&apos;42&apos; is a magic number." source="com.puppycrawl.tools.checkstyle.checks.coding.MagicNumberCheck"/>
                <error line="20" severity="error" message="Missing a Javadoc comment." source="com.puppycrawl.tools.checkstyle.checks.javadoc.MissingJavadocMethodCheck"/>
                <error line="3" severity="ignore" message="off" source="x.Y"/>
                </file>
                <file name="/m/src/main/java/demo/Clean.java"/>
                </checkstyle>
                """);

        List<Finding> findings = Reports.parse(LintTool.CHECKSTYLE, report, List.of(), PmdExclusions.NONE);

        assertThat(findings)
                .containsExactly(
                        new Finding(
                                "warning",
                                "/m/src/main/java/demo/Sample.java",
                                12,
                                9,
                                "MagicNumber",
                                "'42' is a magic number."),
                        new Finding(
                                "error",
                                "/m/src/main/java/demo/Sample.java",
                                20,
                                0,
                                "MissingJavadocMethod",
                                "Missing a Javadoc comment."));
        assertThat(findings.getFirst().text()).isEqualTo("'42' is a magic number. [MagicNumber]");
    }

    @Test
    void detekt_shares_the_checkstyle_format(@TempDir Path tmp) throws Exception {
        Path report = Files.writeString(tmp.resolve("detekt.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <checkstyle version="4.3">
                <file name="/m/src/main/kotlin/demo/App.kt">
                	<error line="7" column="17" severity="warning" message="This expression contains a magic number." source="detekt.MagicNumber" />
                </file>
                </checkstyle>
                """);

        assertThat(Reports.parse(LintTool.DETEKT, report, List.of(), PmdExclusions.NONE))
                .containsExactly(new Finding(
                        "warning",
                        "/m/src/main/kotlin/demo/App.kt",
                        7,
                        17,
                        "MagicNumber",
                        "This expression contains a magic number."));
    }

    /**
     * Maven's {@code excludeFromFailureFile}: a class's listed rules are left out of the report, the
     * rest stay — and the report on disk says the same, so what a guard counts from it is what the
     * step reported.
     */
    @Test
    void pmd_violations_the_exclusion_file_lists_for_their_class_are_left_out(@TempDir Path tmp) throws Exception {
        Path report = Files.writeString(tmp.resolve("pmd.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.27.0">
                <file name="/m/src/main/java/demo/Sample.java">
                <violation beginline="9" endline="9" begincolumn="5" endcolumn="30" rule="UnusedPrivateField" ruleset="Best Practices" package="demo" class="Sample" priority="3">
                Avoid unused private fields such as 'unused'.
                </violation>
                <violation beginline="14" endline="14" begincolumn="1" endcolumn="2" rule="UselessParentheses" ruleset="Code Style" package="demo" class="Sample" priority="4">Useless parentheses.</violation>
                </file>
                </pmd>
                """);
        PmdExclusions excluded = PmdExclusions.read(
                Files.writeString(tmp.resolve("pmd-exclude.properties"), "demo.Sample=UnusedPrivateField\n"));

        assertThat(Reports.parse(LintTool.PMD, report, List.of(), excluded))
                .extracting(Finding::rule)
                .containsExactly("UselessParentheses");
        assertThat(Reports.parse(LintTool.PMD, report, List.of(), PmdExclusions.NONE))
                .as("the report on disk holds the findings the step reported, the excluded ones gone")
                .extracting(Finding::rule)
                .containsExactly("UselessParentheses");
        assertThat(report).content().contains("Useless parentheses.").doesNotContain("UnusedPrivateField");
    }

    @Test
    void pmd_priorities_split_errors_from_warnings_and_a_processing_error_is_an_error(@TempDir Path tmp)
            throws Exception {
        Path report = Files.writeString(tmp.resolve("pmd.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.27.0">
                <file name="/m/src/main/java/demo/Sample.java">
                <violation beginline="9" endline="9" begincolumn="5" endcolumn="30" rule="UnusedPrivateField" ruleset="Best Practices" package="demo" class="Sample" priority="3">
                Avoid unused private fields such as 'unused'.
                </violation>
                <violation beginline="14" endline="14" begincolumn="1" endcolumn="2" rule="AvoidCatchingThrowable" ruleset="Error Prone" priority="1">A catch statement should never catch throwable</violation>
                </file>
                <error filename="/m/src/main/java/demo/Broken.java" msg="ParseException: Syntax error at line 3"/>
                </pmd>
                """);

        assertThat(Reports.parse(LintTool.PMD, report, List.of(), PmdExclusions.NONE))
                .containsExactly(
                        new Finding(
                                "warning",
                                "/m/src/main/java/demo/Sample.java",
                                9,
                                5,
                                "UnusedPrivateField",
                                "Avoid unused private fields such as 'unused'."),
                        new Finding(
                                "error",
                                "/m/src/main/java/demo/Sample.java",
                                14,
                                1,
                                "AvoidCatchingThrowable",
                                "A catch statement should never catch throwable"),
                        new Finding(
                                "error",
                                "/m/src/main/java/demo/Broken.java",
                                0,
                                0,
                                "",
                                "ParseException: Syntax error at line 3"));
    }

    @Test
    void spotbugs_locates_a_bug_through_its_primary_source_line_under_a_root(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src/main/java"));
        Path source = root.resolve("demo/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Sample {}");
        Path report = Files.writeString(tmp.resolve("spotbugs.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <BugCollection version="4.10.4">
                <BugInstance type="EI_EXPOSE_REP" priority="2" rank="18" abbrev="EI" category="MALICIOUS_CODE">
                <ShortMessage>May expose internal representation</ShortMessage>
                <LongMessage>demo.Sample.items() may expose internal representation by returning Sample.items</LongMessage>
                <Class classname="demo.Sample" primary="true">
                <SourceLine classname="demo.Sample" start="5" end="30" sourcefile="Sample.java" sourcepath="demo/Sample.java"/>
                </Class>
                <Method classname="demo.Sample" name="items" signature="()Ljava/util/List;" isStatic="false" primary="true">
                <SourceLine classname="demo.Sample" start="20" end="20" sourcefile="Sample.java" sourcepath="demo/Sample.java"/>
                </Method>
                <SourceLine classname="demo.Sample" primary="true" start="20" end="20" sourcefile="Sample.java" sourcepath="demo/Sample.java"/>
                </BugInstance>
                <BugInstance type="NP_ALWAYS_NULL" priority="1" rank="1" abbrev="NP" category="CORRECTNESS">
                <LongMessage>Null pointer dereference of x in demo.Sample.run()</LongMessage>
                <Class classname="demo.Sample"/>
                <SourceLine classname="demo.Sample" start="41" end="41" sourcefile="Sample.java" sourcepath="demo/Sample.java"/>
                </BugInstance>
                <Errors errors="0" missingClasses="0"/>
                </BugCollection>
                """);

        assertThat(Reports.parse(LintTool.SPOTBUGS, report, List.of(root), PmdExclusions.NONE))
                .containsExactly(
                        new Finding(
                                "warning",
                                source.toString(),
                                20,
                                0,
                                "EI_EXPOSE_REP",
                                "demo.Sample.items() may expose internal representation by returning Sample.items"),
                        new Finding(
                                "error",
                                source.toString(),
                                41,
                                0,
                                "NP_ALWAYS_NULL",
                                "Null pointer dereference of x in demo.Sample.run()"));
    }

    @Test
    void an_empty_report_is_no_finding(@TempDir Path tmp) throws Exception {
        Path report = Files.writeString(tmp.resolve("checkstyle.xml"), "");
        assertThat(Reports.parse(LintTool.CHECKSTYLE, report, List.of(), PmdExclusions.NONE))
                .isEmpty();
    }

    @Test
    void rule_ids_drop_the_check_suffix_and_the_package() {
        assertThat(Reports.ruleOf("com.puppycrawl.tools.checkstyle.checks.blocks.NeedBracesCheck"))
                .isEqualTo("NeedBraces");
        assertThat(Reports.ruleOf("detekt.MagicNumber")).isEqualTo("MagicNumber");
        assertThat(Reports.ruleOf("Check")).isEqualTo("Check");
    }
}
