// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * Quietest console listener: prints only the final pass/fail summary line and any structured
 * errors. Used when the user pipes output, passes {@code --quiet}, or when the plan is marked
 * {@link cc.jumpkick.run.BuildPlan#interactive interactive}.
 */
@RequiredArgsConstructor
public final class SilentListener implements BuildPlanListener {

    private final PrintStream out;
    private final PrintStream err;
    private final boolean suppressDiagnostics;

    public SilentListener(PrintStream out, PrintStream err) {
        this(out, err, false);
    }

    @Override
    public void planFinish(BuildPlanResult result) {
        if (suppressDiagnostics) return;
        List<String> rendered = new ArrayList<>();
        ConsoleSpec.appendErrors(rendered, result.errors());
        for (String line : rendered) err.println(line);
        for (BuildPlanResult.Diagnostic d : result.warnings()) {
            err.println(ConsoleSpec.renderWarning(d));
        }
        // The command body owns the success summary — we don't want to
        // step on the existing "Built ..." / "Created ..." lines. So
        // silent mode is genuinely silent on success.
    }
}
