// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionForms;
import cc.jumpkick.engine.decisions.MachineSetupDecisions;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDecisions;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Os;
import cc.jumpkick.winhost.HostRow;
import cc.jumpkick.winhost.MachineSetup;
import cc.jumpkick.winhost.SetupChange;
import cc.jumpkick.winhost.SetupConsent;
import cc.jumpkick.winhost.SetupPlan;
import cc.jumpkick.winhost.WindowsHostCheck;
import cc.jumpkick.winhost.WindowsSetup;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code doctor} — host health, off the same memoized cache walk as {@code disk}; on Windows also
 * the host conditions that slow or block builds, each with its fix. {@code fix=true} puts the
 * recommended changes to the person as one decision (form elicitation where the host renders forms,
 * else the dashboard and a notification), then applies the ticked ones behind one UAC prompt. A
 * machine that answered it before is not asked again: {@code jk doctor --fix} changes the answer. Only
 * changes from JumpKick's own proposal can be ticked; no path is taken from the call.
 */
public final class DoctorTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "doctor",
                "Host health snapshot (config + disk; on Windows the host rows and their fixes). fix=true asks"
                        + " the user to approve the recommended Windows changes, then shows one Windows (UAC) prompt.",
                McpSchemas.object(Map.of("fix", McpSchemas.bool("Apply the recommended Windows host changes"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        if (in.flag("fix")) return fix(in);
        Map<String, Object> body =
                new LinkedHashMap<>(McpMachine.doctor(in.ctx().cacheSnapshot()));
        body.put("windows", windowsRows(in.dir()).stream().map(HostRow::toMap).toList());
        return in.ok(McpEnvelope.of("doctor", body), "doctor");
    }

    /** The Windows rows for the project at {@code dir}; empty off Windows. */
    static List<HostRow> windowsRows(@Nullable String dir) {
        if (!Os.isWindows()) return List.of();
        return WindowsSetup.check(MachineSetupDecisions.project(dir)).rows();
    }

    private Map<String, Object> fix(McpCall in) {
        if (!Os.isWindows()) return in.text("nothing to set up: the host fixes are for Windows\n");
        WindowsHostCheck check = WindowsSetup.check(MachineSetupDecisions.project(in.dir()));
        SetupConsent consent = WindowsSetup.consent();
        if (consent == SetupConsent.NEVER) {
            try {
                WindowsSetup.machine(check)
                        .decline(WindowsSetup.plan(check, MachineSetupDecisions.served()), "declined");
            } catch (IOException | RuntimeException e) {
                return in.error("machine setup failed: " + Errors.text(e) + "\n");
            }
            return in.text("DECLINED machine setup: " + consent.settledBy() + "; the user changes it there\n");
        }
        SetupPlan whole = WindowsSetup.plan(check, MachineSetupDecisions.served());
        if (whole.isEmpty()) return in.text("this machine is set up: nothing to change\n");
        // An agent asks only about rows the user never answered; jk doctor --fix shows them all.
        SetupPlan plan = in.str(McpDecisions.DECISION) == null ? whole.unanswered(check.memo()) : whole;
        if (consent == SetupConsent.ASK && plan.isEmpty()) {
            return in.text("ANSWERED machine setup was " + check.memo().answered() + "; it is not asked again\n"
                    + "the user changes the answer with `jk doctor --fix`\n");
        }
        List<DecisionForms.Item> items = plan.changes().stream()
                .map(c -> new DecisionForms.Item(c.id(), c.title(), c.why() + " " + c.effect()))
                .toList();
        McpDecisions.Ask ask = new McpDecisions.Ask(
                WindowsSetup.KIND,
                WindowsSetup.QUESTION,
                WindowsSetup.detail("Approve the Windows prompt that follows; it may flash on the taskbar."),
                WindowsSetup.YES,
                items,
                true);
        Decision accepted = null;
        if (consent == SetupConsent.ASK) {
            switch (McpDecisions.require(in, this, ask)) {
                case McpDecisions.Outcome.Stop s -> {
                    return s.result();
                }
                case McpDecisions.Outcome.Proceed p -> accepted = p.decision();
            }
        }
        Set<String> chosen = new LinkedHashSet<>();
        if (accepted == null) plan.changes().forEach(c -> chosen.add(c.id()));
        else chosen.addAll(DecisionForms.chosen(accepted.answers()));
        try {
            MachineSetup.Applied applied = WindowsSetup.machine(check).apply(plan, chosen);
            StringBuilder out =
                    new StringBuilder(applied.declined() ? "DECLINED machine setup\n" : "APPLIED machine setup\n");
            for (MachineSetup.Result r : applied.results())
                out.append("  ").append(r.line()).append('\n');
            for (SetupChange left : plan.changes()) {
                if (!chosen.contains(left.id()))
                    out.append("  left out: ").append(left.title()).append('\n');
            }
            return in.text(out.toString());
        } catch (IOException | RuntimeException e) {
            return in.error("machine setup failed: " + Errors.text(e) + "\n");
        }
    }
}
