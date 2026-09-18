// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import java.util.Locale;

/**
 * The tools {@code [lint]} runs, each one step named {@code lint-<tool>} forking one
 * step-dependency artifact — the tool's runtime closure — and writing one XML report the step
 * reads back.
 */
public enum LintTool {
    CHECKSTYLE("com.puppycrawl.tools.checkstyle.Main", "checkstyle.xml"),
    PMD("net.sourceforge.pmd.cli.PmdCli", "pmd.xml"),
    SPOTBUGS("edu.umd.cs.findbugs.FindBugs2", "spotbugs.xml"),
    DETEKT("io.gitlab.arturbosch.detekt.cli.Main", "detekt.xml");

    private final String main;
    private final String report;

    LintTool(String main, String report) {
        this.main = main;
        this.report = report;
    }

    /** The tool's name in the table, the manifest and the step: {@code checkstyle}, {@code pmd}, …. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The step's name on the plan: {@code lint-<tool>}, the name the manifest's {@code for-step} uses. */
    public String stepName() {
        return "lint-" + id();
    }

    /** The class the forked JVM runs. */
    public String main() {
        return main;
    }

    /** The report file the tool writes under the step's output dir. */
    public String report() {
        return report;
    }

    /** The step's output dir under its scratch: {@code lint/<tool>}. */
    public String out() {
        return "lint/" + id();
    }
}
