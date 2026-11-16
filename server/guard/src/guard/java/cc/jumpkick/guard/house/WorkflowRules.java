// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.house;

import cc.jumpkick.guard.api.Fixture;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import java.util.List;
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
        v.population(workflows.size());
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
