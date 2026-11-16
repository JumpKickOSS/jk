// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.house;

import cc.jumpkick.guard.api.Fixture;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hygiene every workflow under {@code .github/workflows/} is held to, the same three rules
 * {@code scripts/check-workflows.sh} applies in CI's workflow-lint job, so a contributor's {@code jk
 * guard} refuses a workflow edit before the push does; and that a job runs the tree's suites under
 * the checkout's own jk, not the release it bootstraps from. Read from the raw lines: the release tag a
 * pin stands for is a YAML comment, and the comment is part of the rule.
 */
@GuardSuite(scope = Scope.WORKSPACE)
final class WorkflowRules {

    // ---- G104 --------------------------------------------------------------------------------

    private static final String WORKFLOWS = ".github/workflows/*.yml";
    private static final String ACTIONS = ".github/actions/*/action.yml";
    private static final Pattern USES = Pattern.compile("^\\s*(?:-\\s*)?uses:\\s*(.*)$");
    private static final Pattern SHA_PIN = Pattern.compile("[^@]+@[0-9a-f]{40}");
    private static final Pattern DIGEST_PIN = Pattern.compile("docker://[^@]+@sha256:[0-9a-f]{64}");
    private static final Pattern TAG_COMMENT = Pattern.compile("^#\\s*v?[0-9]+(\\.[0-9]+)*");
    private static final Pattern TOP_PERMISSIONS = Pattern.compile("^permissions:\\s*(.*)$");
    private static final Pattern TOP_SCOPE = Pattern.compile("^\\s+([a-z-]+):\\s*([a-z]+)");
    private static final Pattern JOB_PERMISSIONS = Pattern.compile("^\\s+permissions:\\s*(.*)$");
    private static final Pattern JOB_SCOPE_WRITE_ALL = Pattern.compile("^\\s+([a-z-]+):\\s*write-all");

    @Guard(
            id = "workflow-pins-permissions",
            why =
                    "a `uses:` naming a tag or a branch is a name someone else can move under the job that holds the signing key, and a workflow whose token can write by default lets a job that says nothing write anything",
            instead =
                    "pin the action to its full commit sha with the release it stands for in a trailing comment (uses: owner/action@<40 hex> # vX.Y.Z; a docker:// image by @sha256: digest), open every workflow with a read-only top-level permissions: block, and elevate one named scope under the job that writes")
    @Fixture("server/guard/fixtures/workflow-pins-permissions")
    void workflowPinsPermissions(Text text, Violations v) {
        List<String> workflows = text.files(WORKFLOWS);
        for (String wf : workflows) {
            List<String> lines = text.lines(wf);
            uses(wf, lines, v);
            permissions(wf, lines, v);
        }
        // A composite action's steps run in the workflow that uses it: its uses: are pinned the same.
        List<String> actions = text.files(ACTIONS);
        for (String action : actions) uses(action, text.lines(action), v);
        v.population(workflows.size() + actions.size());
    }

    /**
     * Every {@code uses:} names an action by a full 40-hex commit sha with its release tag in a trailing
     * comment; a {@code docker://} image carries an {@code @sha256:} digest; a {@code ./} path is the
     * checkout's own action and needs no pin.
     */
    private static void uses(String wf, List<String> lines, Violations v) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = USES.matcher(lines.get(i));
            if (!m.matches()) continue;
            String value = m.group(1);
            String comment = "";
            int hash = value.indexOf('#');
            if (hash >= 0) {
                comment = value.substring(hash);
                value = value.substring(0, hash);
            }
            value = unquote(value.strip());
            String message;
            if (value.startsWith("./")) continue;
            else if (value.startsWith("docker://"))
                message = DIGEST_PIN.matcher(value).matches()
                        ? null
                        : "uses: " + value + " — a docker:// image must carry an @sha256: digest";
            else if (!SHA_PIN.matcher(value).matches())
                message = "uses: " + value
                        + " — pin the action to a full commit sha (uses: owner/action@<40 hex> # vX.Y.Z)";
            else if (!TAG_COMMENT.matcher(comment).find())
                message = "uses: " + value + " — say which release the sha is, in a trailing comment (# vX.Y.Z)";
            else message = null;
            if (message != null) v.add(new TextSite(wf, i + 1, value), wf + ": " + message);
        }
    }

    /**
     * Every workflow opens with a top-level {@code permissions:} whose scopes are all {@code read} (or
     * {@code read-all}, or {@code {}}); a job that writes names the scope under its own {@code
     * permissions:}; {@code write-all} is refused at either level.
     */
    private static void permissions(String wf, List<String> lines, Violations v) {
        boolean topSeen = false;
        boolean inTop = false;
        boolean inJobs = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.strip().startsWith("#")) continue;
            if (line.startsWith("jobs:")) {
                inJobs = true;
                inTop = false;
            }
            Matcher top = TOP_PERMISSIONS.matcher(line);
            if (top.matches()) {
                topSeen = true;
                String value = stripComment(top.group(1));
                if (value.isEmpty()) inTop = true;
                else if (!value.equals("read-all") && !value.equals("{}"))
                    v.add(
                            new TextSite(wf, i + 1, "permissions: " + value),
                            wf + ": permissions: " + value
                                    + " — the top-level token is read-only: a block of read scopes, read-all, or {}");
                continue;
            }
            if (inTop) {
                Matcher scope = TOP_SCOPE.matcher(line);
                if (scope.find()) {
                    String key = scope.group(1);
                    String value = scope.group(2);
                    if (!value.equals("read") && !value.equals("none"))
                        v.add(
                                new TextSite(wf, i + 1, key + ": " + value),
                                wf + ": permissions: " + key + ": " + value
                                        + " — the top-level block grants read only; a job that writes elevates under its own permissions:");
                    continue;
                }
                if (!line.isBlank()) inTop = false;
            }
            if (!inJobs) continue;
            Matcher job = JOB_PERMISSIONS.matcher(line);
            if (job.matches() && stripComment(job.group(1)).contains("write-all"))
                v.add(
                        new TextSite(wf, i + 1, "permissions: write-all"),
                        wf + ": permissions: write-all — name the scopes the job writes");
            Matcher scope = JOB_SCOPE_WRITE_ALL.matcher(line);
            if (scope.find())
                v.add(
                        new TextSite(wf, i + 1, scope.group(1) + ": write-all"),
                        wf + ": " + scope.group(1) + ": write-all — name the scopes the job writes");
        }
        if (!topSeen)
            v.add(
                    new TextSite(wf, 1, "permissions"),
                    wf
                            + ": no top-level permissions: block — every workflow opens with 'permissions:' and 'contents: read'");
    }

    // ---- suites under the checkout's jk ------------------------------------------------------

    private static final Pattern JOB = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");
    private static final Pattern BOOTSTRAP = Pattern.compile("scripts/ci-bootstrap\\.sh");
    private static final Pattern TAKEOVER = Pattern.compile("scripts/ci-takeover\\.sh");
    private static final Pattern SUITES = Pattern.compile("\\bjk (?:test|guard)\\b|scripts/ci-node-windows\\.sh");

    @Guard(
            id = "workflow-suites-under-checkout-jk",
            why =
                    "a job that installs the pinned release and then runs the tree's suites runs them under the release's engine: engine-side fixes the tree carries are not in it, so the job reports what the release does (on Windows CI, jk-cli's suite replayed on 'C:/Program' long after the tree had fixed that)",
            instead =
                    "after scripts/ci-bootstrap.sh, run scripts/ci-takeover.sh (--install-only when the job only needs the checkout's jk installed) before any step that runs jk test, jk guard or a suite script")
    @Fixture("server/guard/fixtures/workflow-suites-under-checkout-jk")
    void workflowSuitesUnderCheckoutJk(Text text, Violations v) {
        List<String> workflows = text.files(WORKFLOWS);
        for (String wf : workflows) {
            List<String> lines = text.lines(wf);
            boolean inJobs = false;
            String job = null;
            boolean bootstrapped = false;
            boolean tookOver = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("jobs:")) {
                    inJobs = true;
                    continue;
                }
                if (!inJobs || line.strip().startsWith("#")) continue;
                Matcher m = JOB.matcher(line);
                if (m.matches()) {
                    job = m.group(1);
                    bootstrapped = false;
                    tookOver = false;
                    continue;
                }
                if (job == null) continue;
                String code = stripComment(line);
                if (BOOTSTRAP.matcher(code).find()) bootstrapped = true;
                else if (TAKEOVER.matcher(code).find()) tookOver = true;
                else if (bootstrapped && !tookOver && SUITES.matcher(code).find())
                    v.add(
                            new TextSite(wf, i + 1, code.strip()),
                            wf + ": job " + job + " runs the tree's suites under the pinned release: " + code.strip()
                                    + " — take over with scripts/ci-takeover.sh first");
            }
        }
        v.population(workflows.size());
    }

    // ---- runners in cost order ---------------------------------------------------------------

    private static final Pattern JOB_KEY = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");
    private static final Pattern RUNS_ON = Pattern.compile("^    runs-on:\\s*(.+)$");
    private static final Pattern NEEDS_INLINE = Pattern.compile("^    needs:\\s*(.+)$");
    private static final Pattern NEEDS_BLOCK = Pattern.compile("^    needs:\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^      -\\s*([A-Za-z0-9_-]+)\\s*$");
    private static final Pattern MATRIX_OS = Pattern.compile("^\\s+(?:-\\s+)?os:\\s*(.+)$");

    /** A job's runners as written: the OSes it can land on, and the jobs it waits for. */
    private record Job(String id, int line, Set<String> oses, List<String> needs) {}

    @Guard(
            id = "workflow-runners-in-cost-order",
            why =
                    "Windows and macOS runners cost several times a Linux one, and a build broken on Linux is broken everywhere: a Windows or macOS job that starts beside the Linux ones spends the expensive minutes on a run Linux has already failed, and a macOS job beside the Windows ones spends them on one Windows has",
            instead =
                    "give every Windows or macOS job a needs: that reaches a Linux job, and every macOS job one that reaches a Windows job when the workflow has any; split a matrix that spans OSes into a job per OS (the shared steps in a composite action under .github/actions/), since needs orders jobs and not the rows of one matrix")
    @Fixture("server/guard/fixtures/workflow-runners-in-cost-order")
    void workflowRunnersInCostOrder(Text text, Violations v) {
        List<String> workflows = text.files(WORKFLOWS);
        for (String wf : workflows) {
            Map<String, Job> jobs = jobs(text.lines(wf));
            boolean anyWindows = jobs.values().stream().anyMatch(j -> j.oses().contains("windows"));
            for (Job job : jobs.values()) {
                if (job.oses().size() > 1) {
                    v.add(
                            new TextSite(wf, job.line(), job.id()),
                            wf + ": job " + job.id() + " runs on " + String.join(" and ", job.oses())
                                    + " in one matrix — needs cannot order its rows; make a job per OS");
                    continue;
                }
                boolean expensive = job.oses().contains("windows") || job.oses().contains("macos");
                if (expensive && !reaches(jobs, job.id(), "linux", new HashSet<>()))
                    v.add(
                            new TextSite(wf, job.line(), job.id()),
                            wf + ": job " + job.id() + " runs on " + String.join("", job.oses())
                                    + " without needing a Linux job first");
                if (job.oses().contains("macos") && anyWindows && !reaches(jobs, job.id(), "windows", new HashSet<>()))
                    v.add(
                            new TextSite(wf, job.line(), job.id()),
                            wf + ": job " + job.id() + " runs on macOS without needing a Windows job first");
            }
        }
        v.population(workflows.size());
    }

    /** Whether {@code id} needs, directly or through the jobs it needs, a job that runs on {@code os}. */
    private static boolean reaches(Map<String, Job> jobs, String id, String os, Set<String> seen) {
        Job job = jobs.get(id);
        if (job == null || !seen.add(id)) return false;
        for (String need : job.needs()) {
            Job n = jobs.get(need);
            if (n == null) continue;
            if (n.oses().contains(os) || reaches(jobs, need, os, seen)) return true;
        }
        return false;
    }

    /** The jobs of one workflow, read from its lines: each job's runner OSes and its needs. */
    static Map<String, Job> jobs(List<String> lines) {
        Map<String, Job> out = new LinkedHashMap<>();
        boolean inJobs = false;
        String id = null;
        int at = 0;
        Set<String> oses = new TreeSet<>();
        Set<String> matrixOses = new TreeSet<>();
        List<String> needs = new ArrayList<>();
        boolean runsOnMatrix = false;
        boolean inNeedsBlock = false;
        for (int i = 0; i <= lines.size(); i++) {
            String line = i < lines.size() ? lines.get(i) : "end:";
            if (!inJobs) {
                if (line.startsWith("jobs:")) inJobs = true;
                continue;
            }
            if (line.strip().startsWith("#")) continue;
            Matcher key = JOB_KEY.matcher(line);
            boolean topLevel = !line.isBlank() && !line.startsWith(" ");
            if (key.matches() || topLevel) {
                if (id != null) {
                    Set<String> landed = new TreeSet<>(oses);
                    if (runsOnMatrix) landed.addAll(matrixOses);
                    out.put(id, new Job(id, at, landed, List.copyOf(needs)));
                }
                if (topLevel) break;
                id = key.group(1);
                at = i + 1;
                oses = new TreeSet<>();
                matrixOses = new TreeSet<>();
                needs = new ArrayList<>();
                runsOnMatrix = false;
                inNeedsBlock = false;
                continue;
            }
            if (id == null) continue;
            // The comment goes, the indentation stays: it is what says which key a line is.
            int hash = line.indexOf('#');
            String code = (hash >= 0 ? line.substring(0, hash) : line).stripTrailing();
            if (inNeedsBlock) {
                Matcher item = LIST_ITEM.matcher(code);
                if (item.matches()) {
                    needs.add(item.group(1));
                    continue;
                }
                inNeedsBlock = false;
            }
            Matcher runsOn = RUNS_ON.matcher(code);
            if (runsOn.matches()) {
                String value = runsOn.group(1);
                if (value.contains("matrix.")) runsOnMatrix = true;
                else oses.addAll(osesIn(value));
                continue;
            }
            if (NEEDS_BLOCK.matcher(code).matches()) {
                inNeedsBlock = true;
                continue;
            }
            Matcher inline = NEEDS_INLINE.matcher(code);
            if (inline.matches()) {
                for (String n :
                        inline.group(1).replace("[", "").replace("]", "").split(","))
                    if (!n.isBlank()) needs.add(n.strip());
                continue;
            }
            Matcher os = MATRIX_OS.matcher(code);
            if (os.matches()) matrixOses.addAll(osesIn(os.group(1)));
        }
        return out;
    }

    /** The OS families a runs-on value or a matrix os list names. */
    private static Set<String> osesIn(String value) {
        Set<String> out = new TreeSet<>();
        String v = value.toLowerCase(Locale.ROOT);
        if (v.contains("ubuntu") || v.contains("linux")) out.add("linux");
        if (v.contains("windows")) out.add("windows");
        if (v.contains("macos")) out.add("macos");
        return out;
    }

    private static String stripComment(String value) {
        int hash = value.indexOf('#');
        return (hash >= 0 ? value.substring(0, hash) : value).strip();
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))))
            return value.substring(1, value.length() - 1);
        return value;
    }
}
