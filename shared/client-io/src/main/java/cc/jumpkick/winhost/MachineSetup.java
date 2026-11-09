// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies the changes a person chose from a {@link SetupPlan}: everything that needs elevation in
 * one elevated script behind one UAC prompt, then the per-user changes, and records the outcome in
 * the {@link SetupMemo}. Declining, at the decision or at the prompt, applies nothing and records the
 * decline.
 *
 * @param memoFile {@code <state>/windows-host.toml}
 * @param resultFile where the elevated script writes its outcome lines
 */
public record MachineSetup(
        Elevator elevator,
        UserCommands user,
        Forbidden rules,
        Path memoFile,
        Path resultFile,
        Clock clock,
        ScanCost.Probe probe) {

    /** How long the prompt and the script may take together. */
    public static final Duration WAIT = Duration.ofMinutes(5);

    /**
     * What applying one change did. {@code change} is its {@link SetupChange#id}, or empty for a line
     * about the whole run (the prompt, the commands to run by hand).
     */
    public record Result(String change, boolean ok, String line) {}

    /** What applying did, one result per change, and the memo as now written. */
    public record Applied(List<Result> results, SetupMemo memo, boolean declined) {
        public Applied {
            results = List.copyOf(results);
        }

        /** The results as a person reads them, one line each. */
        public List<String> lines() {
            return results.stream().map(Result::line).toList();
        }
    }

    /** This machine's setup, its memo and result under {@code state}. */
    public static MachineSetup live(Forbidden rules, Path state) {
        return new MachineSetup(
                Elevator.live(),
                UserCommands.live(),
                rules,
                SetupMemo.file(state),
                state.resolve("windows-host-result.txt"),
                Clock.SYSTEM,
                ScanCost.LIVE);
    }

    /** Applies {@code chosen} from {@code plan}; the rest of the plan's roots are recorded as left out. */
    public Applied apply(SetupPlan plan, Set<String> chosen) throws IOException {
        List<SetupChange> picked = plan.chosen(chosen);
        List<String> roots = SetupPlan.roots(picked);
        boolean longPaths = picked.stream().anyMatch(c -> c.kind() == SetupChange.Kind.LONG_PATHS);
        List<Result> lines = new ArrayList<>();
        Map<String, String> idOf = new LinkedHashMap<>();
        for (SetupChange c : picked) if (c.path() != null) idOf.put(c.path(), c.id());
        SetupMemo before = SetupMemo.read(memoFile);
        Set<String> verified = new LinkedHashSet<>(before.verified());
        Set<String> missing = new LinkedHashSet<>(before.notExcluded());
        Map<String, String> refused = new LinkedHashMap<>(before.refused());
        long checked = before.checkedAt();
        long beforeMs = before.beforeMs();
        long afterMs = before.afterMs();
        if (!roots.isEmpty() || longPaths) {
            Files.deleteIfExists(resultFile);
            String script = ElevatedScript.render(roots, longPaths, resultFile.toString(), rules);
            Elevator.Outcome outcome = elevator.run(script, WAIT);
            switch (outcome) {
                case Elevator.Outcome.Declined d -> {
                    return decline(plan, "declined at the Windows prompt; nothing changed");
                }
                case Elevator.Outcome.Failed f -> {
                    lines.add(new Result("", false, "could not elevate: " + f.message() + "; nothing changed"));
                    lines.add(new Result("", false, "run these in an admin PowerShell instead:"));
                    for (String command : ElevatedScript.manual(roots, longPaths)) {
                        lines.add(new Result("", false, "  " + command));
                    }
                    return new Applied(lines, before, false);
                }
                case Elevator.Outcome.TimedOut t -> {
                    lines.add(new Result(
                            "",
                            false,
                            "the Windows prompt was not answered in " + WAIT.toMinutes() + " minutes; rerun"
                                    + " `jk doctor --fix`"));
                    return new Applied(lines, before, false);
                }
                case Elevator.Outcome.Ran r -> {
                    checked = clock.millis();
                    record(lines, idOf, verified, missing, refused);
                    String measuredIn = plan.measuredIn();
                    if (plan.measuredMs() > 0 && measuredIn != null && verified.contains(measuredIn)) {
                        long after = probe.perThousand(Path.of(measuredIn));
                        if (after > 0) {
                            beforeMs = plan.measuredMs();
                            afterMs = after;
                            lines.add(new Result(
                                    SetupChange.excludeId(measuredIn),
                                    true,
                                    "build files in " + measuredIn + ": " + ScanCost.seconds(beforeMs) + " -> "
                                            + ScanCost.seconds(afterMs) + " per 1,000 with the exclusion"));
                        }
                    }
                    if (lines.isEmpty()) {
                        lines.add(new Result(
                                "", false, "the elevated script exited " + r.exit() + " and reported nothing"));
                    }
                }
            }
        }
        for (SetupChange c : picked) {
            switch (c.kind()) {
                case GIT_LONG_PATHS -> {
                    boolean ok = user.gitLongPaths();
                    lines.add(new Result(
                            c.id(), ok, ok ? "turned on long paths in git" : "could not set git core.longpaths"));
                }
                case EXECUTION_POLICY -> {
                    boolean ok = user.executionPolicy();
                    lines.add(new Result(
                            c.id(),
                            ok,
                            ok
                                    ? "set your PowerShell execution policy to RemoteSigned"
                                    : "could not set your PowerShell execution policy"));
                }
                default -> {}
            }
        }
        Set<String> leftOut = new LinkedHashSet<>(before.declined());
        leftOut.removeAll(roots);
        for (String root : SetupPlan.roots(plan.changes())) if (!roots.contains(root)) leftOut.add(root);
        SetupMemo memo = new SetupMemo(
                SetupMemo.Answer.ACCEPTED,
                clock.millis(),
                checked,
                List.copyOf(verified),
                List.copyOf(missing),
                List.copyOf(leftOut),
                refused,
                beforeMs,
                afterMs);
        memo.write(memoFile);
        return new Applied(lines, memo, false);
    }

    /** Records that the person declined {@code plan}: nothing is applied and its roots are left out. */
    public Applied decline(SetupPlan plan, String line) throws IOException {
        SetupMemo before = SetupMemo.read(memoFile);
        Set<String> declined = new LinkedHashSet<>(before.declined());
        declined.addAll(SetupPlan.roots(plan.changes()));
        SetupMemo memo = new SetupMemo(
                SetupMemo.Answer.DECLINED,
                clock.millis(),
                before.checkedAt(),
                before.verified(),
                before.notExcluded(),
                List.copyOf(declined),
                before.refused(),
                before.beforeMs(),
                before.afterMs());
        memo.write(memoFile);
        return new Applied(List.of(new Result("", true, line)), memo, true);
    }

    /** Reads the elevated script's outcome lines into {@code lines} and the memo's sets. */
    private void record(
            List<Result> lines,
            Map<String, String> idOf,
            Set<String> verified,
            Set<String> missing,
            Map<String, String> refused)
            throws IOException {
        for (String line : readResult()) {
            String[] f = line.split("\t", 3);
            String what = f[0], subject = f.length > 1 ? f[1] : "", why = f.length > 2 ? f[2] : "";
            String id = idOf.getOrDefault(subject, "");
            switch (what) {
                case "verified" -> {
                    verified.add(subject);
                    missing.remove(subject);
                    refused.remove(subject);
                    lines.add(new Result(id, true, "excluded " + subject + " from Defender (verified)"));
                }
                case "missing" -> {
                    missing.add(subject);
                    verified.remove(subject);
                    lines.add(new Result(id, false, "not excluded " + subject + ": " + why));
                }
                case "refused", "failed" -> {
                    refused.put(subject, why);
                    lines.add(new Result(id, false, what + " " + subject + ": " + why));
                }
                case "longpaths" ->
                    lines.add(new Result(
                            SetupChange.LONG_PATHS,
                            subject.equals("ok"),
                            subject.equals("ok") ? "turned on long paths in Windows" : "long paths: " + why));
                default -> {}
            }
        }
    }

    private List<String> readResult() throws IOException {
        if (!Files.isRegularFile(resultFile)) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(resultFile, StandardCharsets.UTF_8)) {
            String l = line.strip();
            if (l.startsWith("﻿")) l = l.substring(1);
            if (!l.isEmpty()) out.add(l);
        }
        Files.deleteIfExists(resultFile);
        return out;
    }
}
