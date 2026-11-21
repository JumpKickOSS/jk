// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.engine.WireStream;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceGroup;
import cc.jumpkick.docs.AgentsGuide;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.run.SourceLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A command run at a group root ({@link WorkspaceGroup}): a build verb runs in every member the
 * selection names, one after another in the order their {@code path} dependencies give, against the
 * one engine, each run exactly the one {@code cd <member> && jk <verb>} makes; then one line totals
 * them. {@code -m} names members (paths or artifact names) instead of modules; {@code -p} naming a
 * root profile picks the members, any other {@code -p} reaches every member as written. {@code jk
 * release} and {@code jk publish} are refused: a release belongs to one workspace.
 *
 * <p>A member that fails leaves the members that depend on it unrun; the others still run, and the
 * total names both. Excluded members the selection leaves out are named first, each with its reason
 * and the profile that includes it.
 */
final class GroupRun {

    /** The verbs that run over the members. */
    static final Set<String> VERBS = Set.copyOf(WorkspaceGroup.ROOT_VERBS);

    /** The verbs refused at a group root: each belongs to one workspace. */
    static final Set<String> REFUSED = Set.of("release", "publish");

    /** The agent report a group run composed, read once by dispatch in place of the last job's. */
    private static @Nullable String agentReport;

    private GroupRun() {}

    /**
     * The group root {@code qualified} (a top-level verb, {@code jk build}) runs at under {@code in},
     * or {@code null} when it is not a group verb or its directory is no group root.
     */
    static @Nullable Path rootFor(String qualified, Invocation in) {
        String[] words = qualified.split(" ");
        if (words.length != 2) return null;
        if (!VERBS.contains(words[1]) && !REFUSED.contains(words[1])) return null;
        Path dir = WorkingDirectory.resolve(in.value("dir")
                .map(Path::of)
                .orElseGet(() -> SessionContext.current().config().dirOr(WorkingDirectory.current())));
        return WorkspaceGroup.declares(dir) ? dir : null;
    }

    /** The report {@link #run} composed for agent mode, once; {@code null} when none was. */
    static @Nullable String takeAgentReport() {
        String report = agentReport;
        agentReport = null;
        return report;
    }

    /** The line a member's manifest refusal prints: one located in a file already leads with it. */
    static @Nullable String refusal(String member, JkBuildParseException e) {
        SourceLocation at = e.location();
        return at != null && !at.file().isEmpty() ? e.getMessage() : member + ": " + e.getMessage();
    }

    /** What one member's run came to. */
    private record Outcome(
            WorkspaceGroup.Member member,
            int exit,
            boolean upToDate,
            @Nullable String blockedBy) {}

    static int run(CliCommand cmd, Path root, Invocation in) throws Exception {
        String verb = cmd.name();
        String group = root.getFileName() == null
                ? root.toString()
                : root.getFileName().toString();
        if (REFUSED.contains(verb)) {
            CommandWedge.printFail(
                    verb,
                    group + " groups workspaces and has no version of its own; jk " + verb
                            + " runs in the workspace it releases: jk -C <member> " + verb);
            return Exit.CONFIG;
        }
        WorkspaceGroup members;
        try {
            members = WorkspaceGroup.load(root);
        } catch (JkBuildParseException | IOException e) {
            CommandWedge.printFail(verb, e.getMessage());
            return Exit.CONFIG;
        }
        if (!members.missing().isEmpty()) {
            CommandWedge.printFail(
                    verb,
                    "[workspaces] members lists " + String.join(", ", members.missing())
                            + ", which holds no project; fix the list (jk doctor checks it)");
            return Exit.CONFIG;
        }
        String profile = in.value("profile").orElse(null);
        String rootProfile = profile != null && members.profiles().containsKey(profile) ? profile : null;
        List<String> names = names(CommonOpts.modulesSpec(in));
        WorkspaceGroup.Selection selection;
        try {
            selection = members.select(rootProfile, names);
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail(verb, e.getMessage());
            return Exit.CONFIG;
        }
        refreshAgentsGuide(root);
        boolean human = !CliOutput.scriptMode();
        // The agent report says what was skipped first too: an agent reads it in place of this output.
        StringBuilder report = new StringBuilder();
        for (WorkspaceGroup.Member m : selection.skipped()) {
            String by = members.includedBy(m);
            String why = m.path() + ": " + m.excludedBecause()
                    + (by == null ? "" : " (jk " + verb + " -p " + by + " includes it)");
            if (human) CommandWedge.printChip(Glyphs.WAITING, "skip", why);
            report.append("SKIP ").append(why).append('\n');
        }
        // What every member's run is handed: the same flags, its own directory, no member names, and
        // the root's profile consumed here.
        Invocation base = in.without("modules");
        if (rootProfile != null) base = base.without("profile");
        long start = Clock.SYSTEM.nanos();
        Map<Path, Outcome> outcomes = new LinkedHashMap<>();
        Set<Path> broken = new HashSet<>();
        for (WorkspaceGroup.Member m : selection.run()) {
            WorkspaceGroup.Member blocker = firstBroken(members, selection, m, broken);
            if (blocker != null) {
                broken.add(m.dir());
                outcomes.put(m.dir(), new Outcome(m, Exit.SOFTWARE, false, blocker.path()));
                if (human) {
                    CommandWedge.printChip(
                            Glyphs.WAITING, "skip", m.path() + ": not run, it depends on " + blocker.path());
                }
                report.append("SKIP ")
                        .append(m.path())
                        .append(": depends on ")
                        .append(blocker.path())
                        .append('\n');
                continue;
            }
            WireStream.forgetLastRun();
            if (human) CommandWedge.printChip(Glyphs.PLAY, verb, m.path());
            int code;
            try {
                code = cmd.run(base.withValue("dir", m.dir().toString()));
            } catch (JkBuildParseException e) {
                CommandWedge.printFail(verb, refusal(m.path(), e));
                code = Exit.CONFIG;
            }
            boolean upToDate = WireStream.lastRunUpToDate();
            String own = CommandDispatch.lastRunReport();
            report.append(own == null ? (code == 0 ? "OK " : "FAIL ") + verb + " " + m.path() + "\n" : own);
            if (report.charAt(report.length() - 1) != '\n') report.append('\n');
            if (code != 0) broken.add(m.dir());
            outcomes.put(m.dir(), new Outcome(m, code, upToDate, null));
        }
        Duration took = Duration.ofNanos(Clock.SYSTEM.nanos() - start);
        int exit = 0;
        for (Outcome o : outcomes.values()) {
            if (o.exit() != 0 && o.blockedBy() == null) {
                exit = o.exit();
                break;
            }
        }
        String total = total(group, outcomes.values(), took);
        report.append(exit == 0 ? "OK " : "FAIL ")
                .append(verb)
                .append(' ')
                .append(total)
                .append('\n');
        agentReport = report.toString();
        if (CliOutput.jsonMode()) {
            emitFinish(exit, outcomes.values(), selection.skipped(), took);
        } else if (exit == 0) {
            CommandWedge.printOk(verb, total);
        } else {
            CommandWedge.printFail(verb, total);
        }
        return exit;
    }

    /** The comma-joined {@code -m} spec as names, blank ones dropped. */
    static List<String> names(@Nullable String spec) {
        if (spec == null || spec.isBlank()) return List.of();
        return Arrays.stream(spec.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** The first member {@code m} depends on that failed or did not run, or {@code null}. */
    private static WorkspaceGroup.@Nullable Member firstBroken(
            WorkspaceGroup group, WorkspaceGroup.Selection selection, WorkspaceGroup.Member m, Set<Path> broken) {
        for (Path prereq : selection.prerequisites(m)) {
            if (broken.contains(prereq)) {
                for (WorkspaceGroup.Member other : group.members())
                    if (other.dir().equals(prereq)) return other;
            }
        }
        return null;
    }

    /** The total's tail: {@code examples · 12 workspaces, 11 up to date · 1.2s}, or what failed. */
    static String total(String group, Collection<Outcome> outcomes, Duration took) {
        int n = outcomes.size();
        List<String> failed = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        int upToDate = 0;
        for (Outcome o : outcomes) {
            if (o.blockedBy() != null) blocked.add(o.member().path());
            else if (o.exit() != 0) failed.add(o.member().path());
            else if (o.upToDate()) upToDate++;
        }
        StringBuilder tail =
                new StringBuilder(group).append(" · ").append(n).append(n == 1 ? " workspace" : " workspaces");
        if (!failed.isEmpty()) {
            tail.append(", ").append(failed.size()).append(" failed: ").append(String.join(", ", failed));
            if (!blocked.isEmpty()) tail.append("; not run: ").append(String.join(", ", blocked));
        } else if (upToDate == n && n > 0) {
            tail.append(" · up to date");
        } else if (upToDate > 0) {
            tail.append(", ").append(upToDate).append(" up to date");
        }
        return tail.append(" · ").append(ConsoleSpec.fmtDuration(took)).toString();
    }

    private static void emitFinish(
            int exit, Collection<Outcome> outcomes, List<WorkspaceGroup.Member> skipped, Duration took) {
        List<String> ok = new ArrayList<>();
        List<String> upToDate = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> notRun = new ArrayList<>();
        for (Outcome o : outcomes) {
            String path = o.member().path();
            if (o.blockedBy() != null) notRun.add(path);
            else if (o.exit() != 0) failed.add(path);
            else {
                ok.add(path);
                if (o.upToDate()) upToDate.add(path);
            }
        }
        Map<String, String> skippedReasons = new LinkedHashMap<>();
        for (WorkspaceGroup.Member m : skipped) skippedReasons.put(m.path(), m.excludedBecause());
        JsonlShape.row("group-finish", f -> f.bool("success", exit == 0)
                .number("exitCode", exit)
                .array("ok", ok)
                .array("upToDate", upToDate)
                .array("failed", failed)
                .array("notRun", notRun)
                .map("skipped", skippedReasons)
                .number("millis", took.toMillis()));
    }

    /**
     * An upgraded jk brings its section of the root's {@code AGENTS.md} up to date, as a build does
     * in a workspace; a failure never stops the run.
     */
    private static void refreshAgentsGuide(Path root) {
        try {
            if (AgentsGuide.refresh(root) == AgentsGuide.Change.UPDATED) {
                CliOutput.err("updated JumpKick's section of " + root.resolve(AgentsGuide.FILE));
            }
        } catch (IOException ignored) {
            // A read-only or vanished AGENTS.md is the project's business, not the run's.
        }
    }
}
