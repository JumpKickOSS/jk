// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.maven;

import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.test.MarkdownTestReport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A finished Maven run as the journal sees it: per module an outcome, a step chain, the error
 * diagnostics its failed mojo explains, and the tests its report XML recorded.
 */
public record MavenRunReport(List<Module> modules) {

    /** The step name every Maven module row carries. */
    public static final String PLAN_NAME = "mvn";

    public MavenRunReport {
        modules = List.copyOf(modules);
    }

    /** One reactor module, ready to feed the accumulator. */
    public record Module(
            String coord,
            Path dir,
            boolean success,
            long millis,
            List<MavenEvents.Step> steps,
            List<BuildPlanResult.Diagnostic> errors,
            List<MarkdownTestReport.Entry> tests) {}

    /** True when the run recorded no module at all ({@code mvn -v}, a POM that failed to parse). */
    public boolean isEmpty() {
        return modules.isEmpty();
    }

    /** Read the spy's {@code events} file and each module's report XML under {@code projectDir}. */
    public static MavenRunReport read(Path projectDir, Path events) throws IOException {
        List<Module> out = new ArrayList<>();
        for (MavenEvents.Module m : MavenEvents.modules(MavenEvents.read(events))) {
            Path dir = m.dir().isEmpty() ? projectDir : Path.of(m.dir());
            List<MarkdownTestReport.Entry> tests = m.skipped() ? List.of() : SurefireReports.read(dir);
            out.add(new Module(m.coord(), dir, m.success(), m.millis(), m.steps(), errorsOf(m, tests), tests));
        }
        return new MavenRunReport(out);
    }

    /** The run's test counts, or {@code null} when no module recorded a test. */
    public TestSummary tests() {
        long total = 0, failed = 0, skipped = 0;
        for (Module m : modules) {
            for (MarkdownTestReport.Entry e : m.tests()) {
                total++;
                if (e.isFail()) failed++;
                else if (e.isSkip()) skipped++;
            }
        }
        return new TestSummary(total, total - failed - skipped, failed, skipped, List.of());
    }

    /**
     * Compiler sites when the failed mojo reported any; nothing when the failure is the test
     * failure the Tests section already shows; otherwise the mojo's own message.
     */
    static List<BuildPlanResult.Diagnostic> errorsOf(MavenEvents.Module m, List<MarkdownTestReport.Entry> tests) {
        MavenEvents.Failure f = m.failure();
        if (f == null) return List.of();
        List<BuildPlanResult.Diagnostic> out = new ArrayList<>();
        for (MavenCompilerDiagnostics.Site site : MavenCompilerDiagnostics.parse(f.message())) {
            out.add(new BuildPlanResult.Diagnostic(f.goal(), "compiler", site.asHeader()));
        }
        if (!out.isEmpty()) return out;
        boolean testsExplainIt = tests.stream().anyMatch(MarkdownTestReport.Entry::isFail);
        if (testsExplainIt) return List.of();
        String step = f.goal().isEmpty() ? PLAN_NAME : f.goal();
        return List.of(new BuildPlanResult.Diagnostic(
                step, "mojo", f.message(), "", f.exception(), "", "", "", "", "", "", 0, 0, List.of(), 0));
    }
}
