// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.mcp.McpDiagnostics;
import cc.jumpkick.run.TaskNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code ## Guards} block: the agent view of the house rules, within its token budget. */
class JkResultsGuardsTest {

    private static final String SITE = """
            server/engine/src/main/java/cc/jumpkick/engine/Cas.java:88: MessageDigest.getInstance("SHA-256") called outside cc.jumpkick.host.Hashing
              Instead:  Hashing.sha256Hex
              Why:      one algorithm table, one place to swap the provider
              At:       cc.jumpkick.engine.Cas#put([B)V -> java.security.MessageDigest#getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;
              Baseline: new
              Source:   jk-guards.toml:12
              Exempt:   ask the user to add [guards.one-digest-surface].allow with a reason
              Explain:  jk guard explain one-digest-surface""";

    private static BuildRecord.Diag site(String code, String file, int line, String message) {
        return new BuildRecord.Diag(
                "error",
                "/ws",
                TaskNames.GUARD,
                code,
                message,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                file,
                line,
                0,
                0,
                List.of(),
                0);
    }

    private static BuildRecord record(boolean success, List<BuildRecord.Diag> diags, List<BuildRecord.Task> steps) {
        return new BuildRecord(
                "id",
                3,
                BuildRecord.SCHEMA,
                "build",
                "/ws",
                "g:a",
                "pid",
                1_000,
                1_100,
                100,
                success,
                false,
                success ? 0 : 1,
                "9.9",
                null,
                List.of(),
                steps,
                diags,
                "cli",
                null,
                null,
                null,
                false,
                null,
                0,
                null,
                List.of());
    }

    private static final String THRASHING = SITE.replace(
            "  Exempt:   ask the user to add [guards.one-digest-surface].allow with a reason",
            "  Thrash:   this site has failed on 2 consecutive builds — stop and ask the user whether an `allow` with a"
                    + " reason is right here, or whether the rule needs changing");

    @Test
    void a_thrashing_site_says_so_in_the_group_header_and_under_the_site() {
        BuildRecord.Diag d =
                site("one-digest-surface", "server/engine/src/main/java/cc/jumpkick/engine/Cas.java", 88, THRASHING);
        String md = JkResultsMarkdown.render(
                record(false, List.of(d), List.of(new BuildRecord.Task(TaskNames.GUARD, "compile", "FAIL", 1, 0))));
        assertThat(md)
                .contains(
                        "### one-digest-surface — one algorithm table, one place to swap the provider  (1 site, seen 2 builds running)")
                .contains(
                        "  → Hashing.sha256Hex\n  This site has failed on 2 consecutive builds. Stop and ask the user whether an `allow` with a reason is right here.\n");
        String fresh = JkResultsMarkdown.render(record(
                false,
                List.of(site(
                        "one-digest-surface", "server/engine/src/main/java/cc/jumpkick/engine/Cas.java", 88, SITE)),
                List.of(new BuildRecord.Task(TaskNames.GUARD, "compile", "FAIL", 1, 0))));
        assertThat(fresh).doesNotContain("builds running").doesNotContain("consecutive builds");
    }

    @Test
    void clean_lanes_are_one_line_and_no_section_without_lanes() {
        List<BuildRecord.Task> lanes = List.of(
                new BuildRecord.Task(TaskNames.GUARD, "compile", "SKIPPED", 1, 0),
                new BuildRecord.Task(TaskNames.GUARD_MODEL, "resolve", "SUCCESS", 1, 0));
        String md = JkResultsMarkdown.render(record(true, List.of(), lanes));
        assertThat(md).contains("## Guards\n\nGuards: clean · 2 lanes (1 cached)\n");
        String none = JkResultsMarkdown.render(
                record(true, List.of(), List.of(new BuildRecord.Task("compile-java", "compile", "SUCCESS", 1, 0))));
        assertThat(none).doesNotContain("## Guards");
    }

    @Test
    void red_sites_group_by_rule_with_why_once_and_instead_per_site_and_leave_failures_alone() {
        BuildRecord.Diag d =
                site("one-digest-surface", "server/engine/src/main/java/cc/jumpkick/engine/Cas.java", 88, SITE);
        BuildRecord.Diag other = new BuildRecord.Diag(
                "error", "/ws", "compile-java", "javac", "x.java:1: cannot find symbol", null, null);
        String md = JkResultsMarkdown.render(record(
                false, List.of(d, other), List.of(new BuildRecord.Task(TaskNames.GUARD, "compile", "FAIL", 1, 0))));
        assertThat(md)
                .contains(
                        "## Guards\n\n**1 rule broken** (1 site)\n\n### one-digest-surface — one algorithm table, one place to swap the provider  (1 site)\n");
        assertThat(md)
                .contains(
                        "- `server/engine/src/main/java/cc/jumpkick/engine/Cas.java:88`  MessageDigest.getInstance(\"SHA-256\") called outside cc.jumpkick.host.Hashing\n  → Hashing.sha256Hex\n");
        assertThat(md).contains("Never edit jk-guards-baseline.toml by hand");
        // The guard row is not repeated under ## Failures; the javac row still is.
        int failures = md.indexOf("## Failures");
        assertThat(failures).isGreaterThan(0);
        assertThat(md.substring(failures, md.indexOf("## Guards")))
                .contains("cannot find symbol")
                .doesNotContain("one-digest-surface");
    }

    @Test
    void the_section_stays_within_budget_on_many_sites() {
        List<BuildRecord.Diag> diags = new ArrayList<>();
        for (int r = 0; r < 5; r++) {
            for (int i = 0; i < 8; i++) {
                diags.add(site(
                        "rule-" + r,
                        "m/src/main/java/a/F" + i + ".java",
                        i + 1,
                        "m/src/main/java/a/F" + i + ".java:" + (i + 1) + ": site " + i + " of rule " + r
                                + "\n  Instead:  the owner\n  Why:      reason " + r));
            }
        }
        String md = JkResultsMarkdown.render(
                record(false, diags, List.of(new BuildRecord.Task(TaskNames.GUARD, "compile", "FAIL", 1, 0))));
        String section = md.substring(md.indexOf("## Guards"));
        assertThat(section).contains("**5 rules broken** (40 sites)").contains("_+30 more — target/jk-guards/_");
        assertThat(section.length() / 4).as("rough token count").isLessThan(1_000);
    }

    @Test
    void mcp_rows_carry_typed_instead_and_why_for_guard_diagnostics() {
        Map<String, Object> row = McpDiagnostics.normalize(Map.of(
                "severity",
                "error",
                "dir",
                "/ws",
                "task",
                TaskNames.GUARD,
                "code",
                "one-digest-surface",
                "message",
                SITE,
                "file",
                "",
                "line",
                0L,
                "col",
                0L));
        assertThat(row)
                .containsEntry("code", "one-digest-surface")
                .containsEntry("instead", "Hashing.sha256Hex")
                .containsEntry("why", "one algorithm table, one place to swap the provider");
        assertThat(row.get("file")).isEqualTo("server/engine/src/main/java/cc/jumpkick/engine/Cas.java");
        assertThat(row.get("line")).isEqualTo(88);
        Map<String, Object> javac = McpDiagnostics.normalize(Map.of(
                "severity",
                "error",
                "dir",
                "/ws",
                "task",
                "compile-java",
                "code",
                "javac",
                "message",
                "x.java:1: boom\n  Why: not a guard"));
        assertThat(javac).doesNotContainKey("why");
    }
}
