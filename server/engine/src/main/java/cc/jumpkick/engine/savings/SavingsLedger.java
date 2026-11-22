// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.sessions.ActionRecord;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.TestsNotRerun;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Adds up what jk saved: the cache's estimate and its skipped steps, suites and test classes from
 * each run's record, guard violations from its errors, and the graph questions, refactorings and
 * edits from the sessions' actions, with the tokens those saved the agents ({@link SavingsRates}).
 * Nothing new is recorded for it; it reads what the journal keeps, so a period reaches back only as
 * far as {@code [history]} retention.
 */
public final class SavingsLedger {

    /** The {@code file:line: } a guard site's message starts with. */
    private static final Pattern SITE = Pattern.compile("([^\\n:][^\\n]*?):\\d+: ");

    private SavingsLedger() {}

    /**
     * The rows for the period from {@code since} to {@code until} (epoch millis): {@link
     * Savings#ALL} first, then one per project, the one with the most saved first. {@code sessions}
     * is null for an engine that logs none; {@code usage} finds the model each agent was on.
     */
    public static List<Savings> read(
            BuildJournal journal, @Nullable SessionLog sessions, AgentUsage usage, long since, long until) {
        return fold(
                journal.finishedSince(since),
                sessions == null ? List.of() : sessions.actionsSince(since),
                SavingsRates.current(),
                new AgentModels(usage),
                ProjectLines.counted(),
                since,
                until);
    }

    /** The rows {@link #read} answers, from runs (newest first) and actions already read. */
    static List<Savings> fold(
            List<BuildRecord> runs,
            List<SessionLog.Acted> actions,
            SavingsRates rates,
            AgentModels models,
            ProjectLines lines,
            long since,
            long until) {
        for (SessionLog.Acted a : actions) {
            if (savesTokens(a.action()))
                models.note(a.client(), a.action().project(), a.action().at());
        }
        Savings all = Savings.empty(Savings.ALL, null, null, null, since, until);
        Map<String, Savings> projects = new LinkedHashMap<>();
        Map<String, String> idByDir = new LinkedHashMap<>();
        for (BuildRecord r : runs) {
            if (r.finishedAt() > until) continue;
            String key = key(r.projectId(), r.dir());
            if (r.projectId() != null && r.dir() != null) idByDir.putIfAbsent(r.dir(), r.projectId());
            Savings row = of(r);
            all = all.plus(row);
            // Runs arrive newest first, so a project's row names its newest checkout and coordinate.
            projects.merge(
                    key,
                    Savings.empty(Savings.PROJECT, r.projectId(), r.dir(), r.coord(), since, until)
                            .plus(row),
                    Savings::plus);
        }
        for (SessionLog.Acted a : actions) {
            ActionRecord action = a.action();
            if (action.at() > until) continue;
            Savings row = of(action, saved(action, a.client(), rates, models, lines));
            if (row.nothing()) continue;
            all = all.plus(row);
            String dir = action.project();
            String id = a.projectId() != null ? a.projectId() : dir == null ? null : idByDir.get(dir);
            if (id == null && dir == null) continue;
            projects.merge(
                    key(id, dir),
                    Savings.empty(Savings.PROJECT, id, dir, null, since, until).plus(row),
                    Savings::plus);
        }
        List<Savings> out = new ArrayList<>();
        out.add(all);
        projects.values().stream()
                .sorted((x, y) -> Long.compare(y.savedMillis(), x.savedMillis()))
                .forEach(out::add);
        return out;
    }

    /** A project's key: its id, else the directory it ran in. */
    private static String key(@Nullable String projectId, @Nullable String dir) {
        return projectId != null ? "id:" + projectId : "dir:" + (dir == null ? "" : dir);
    }

    /** One run's contribution, with no identity of its own. */
    static Savings of(BuildRecord r) {
        BuildRecord.CacheBenefit b = r.benefit();
        long steps = 0;
        long suites = 0;
        List<BuildRecord.Task> tasks = new ArrayList<>(r.steps());
        for (BuildRecord.Module m : r.modules()) tasks.addAll(m.steps());
        for (BuildRecord.Task t : tasks) {
            if (!"SKIPPED".equals(t.status())) continue;
            steps++;
            if (TaskNames.isRunTests(t.name())) suites++;
        }
        long classes = 0;
        Set<String> violations = new HashSet<>();
        for (BuildRecord.Diag d : r.diagnostics()) {
            if ("warning".equals(d.severity()) && TestsNotRerun.CODE.equals(d.code())) {
                classes += TestsNotRerun.count(d.message());
            } else if ("error".equals(d.severity()) && TaskNames.isGuardLane(d.step())) {
                violations.add(violation(r, d));
            }
        }
        return new Savings(
                Savings.ALL,
                null,
                null,
                null,
                0,
                0,
                1,
                b == null ? 0 : b.savedMillis(),
                b == null ? 0 : b.estimatedUncachedMillis(),
                b == null ? 0 : b.coveredSkips(),
                b == null ? 0 : b.totalSkips(),
                steps,
                suites,
                classes,
                0,
                0,
                0,
                violations,
                violations.isEmpty() ? 0 : 1,
                TokensSaved.NONE,
                Map.of());
    }

    /**
     * What makes guard error {@code d} of run {@code r} one violation: its rule and the file it
     * names in its module of the checkout the run built, so a rule a file breaks in run after run
     * counts once. A guard site names its file only at the head of its message ({@code
     * src/Main.java:3: …}); an error with no file is its rule's one violation in that module.
     */
    static String violation(BuildRecord r, BuildRecord.Diag d) {
        String file = d.file();
        if (file.isEmpty()) {
            Matcher m = SITE.matcher(d.message());
            if (m.lookingAt()) file = m.group(1);
        }
        return d.code() + '\0' + (r.dir() == null ? "" : r.dir()) + '\0' + d.dir() + '\0' + file;
    }

    /** Whether {@code a} is a call the bench measured a token saving for: a code answer or a refactoring. */
    static boolean savesTokens(ActionRecord a) {
        return ActionRecord.OK.equals(a.outcome())
                && (ActionRecord.GRAPH.contains(a.kind()) || ActionRecord.REFACTORINGS.contains(a.kind()));
    }

    /**
     * The tokens {@code a} saved its agent, on the model {@code models} finds it was on, at the rate
     * for its project's size, matched on the effort level too where its files say it ({@link
     * SavingsRates#saved}).
     */
    static SavingsRates.Credit saved(
            ActionRecord a, @Nullable String client, SavingsRates rates, AgentModels models, ProjectLines lines) {
        if (!savesTokens(a)) return SavingsRates.Credit.NONE;
        AgentUsage.Ran ran = models.ran(client, a.project(), a.at());
        return rates.saved(
                a, client, ran == null ? null : ran.model(), ran == null ? null : ran.effort(), lines.of(a.project()));
    }

    /**
     * One action's contribution: a question answered, a refactoring applied, edits made, or nothing,
     * with the tokens {@code credit} says it saved.
     */
    static Savings of(ActionRecord a, SavingsRates.Credit credit) {
        boolean ok = ActionRecord.OK.equals(a.outcome());
        long graph = ok && ActionRecord.GRAPH.contains(a.kind()) ? 1 : 0;
        long refactoring = ok && ActionRecord.REFACTORINGS.contains(a.kind()) ? 1 : 0;
        return new Savings(
                Savings.ALL,
                null,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                graph,
                refactoring,
                ok ? a.edits() : 0,
                Set.of(),
                0,
                credit.tokens(),
                credit.match() == null ? Map.of() : Map.of(credit.match(), 1L));
    }
}
