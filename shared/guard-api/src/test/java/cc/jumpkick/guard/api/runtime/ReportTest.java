// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.api.CallSite;
import cc.jumpkick.guard.api.MetricSite;
import cc.jumpkick.guard.api.Origin;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.ToolSite;
import cc.jumpkick.guard.facts.Calls;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportTest {

    private static Report.Line line(Report.Collector c) {
        return new Report.Line(
                "one-json-codec",
                "escaping by hand",
                "Jsonl.quote",
                "a.Suite#codec",
                Scope.WORKSPACE,
                List.of(new Report.AllowEntry("shared/host", "the owner")),
                null,
                List.of("Facts", "Violations"),
                c);
    }

    private static CallSite bytecodeSite() {
        ClassFacts esc = new ClassFacts(
                "a/Esc", 1, "java/lang/Object", List.of(), "Esc.java", List.of(), List.of(), List.of(), Set.of());
        MethodFacts write = new MethodFacts("write", "()V", 1, List.of(), List.of(), List.of(), List.of(), 0, 0);
        return new CallSite(
                new Origin(esc, write),
                Calls.call("java/lang/String", "replace", "(CC)Ljava/lang/String;", 7, "\"", 1));
    }

    @Test
    void a_clean_guard_writes_its_declaration_and_no_violations() {
        Report.Collector c = new Report.Collector();
        Object json = MiniJson.parse(line(c).toJson());
        assertThat(MiniJson.str(json, "id")).isEqualTo("one-json-codec");
        assertThat(MiniJson.str(json, "why")).isEqualTo("escaping by hand");
        assertThat(MiniJson.str(json, "instead")).isEqualTo("Jsonl.quote");
        assertThat(MiniJson.str(json, "source")).isEqualTo("a.Suite#codec");
        assertThat(MiniJson.str(json, "scope")).isEqualTo("workspace");
        assertThat(MiniJson.str(json, "outcome")).isEqualTo("ok");
        assertThat(MiniJson.get(json, "params")).isEqualTo(List.of("Facts", "Violations"));
        assertThat((List<?>) MiniJson.get(json, "violations")).isEmpty();
        assertThat(MiniJson.get(json, "population"))
                .as("unset until the guard says")
                .isNull();
        assertThat(MiniJson.get(json, "fixture")).isNull();
        assertThat(MiniJson.get(json, "error")).isNull();
        List<?> allows = (List<?>) requireNonNull(MiniJson.get(json, "allows"));
        assertThat(allows).hasSize(1);
        assertThat(MiniJson.str(allows.get(0), "in")).isEqualTo("shared/host");
        assertThat(MiniJson.str(allows.get(0), "reason")).isEqualTo("the owner");
    }

    @Test
    void bytecode_sites_are_rooted_at_a_source_root_and_text_sites_at_the_workspace() {
        Report.Collector c = new Report.Collector();
        c.add(bytecodeSite(), "an escaper");
        c.add(new TextSite("docs/x.md", 3, "  TODO "), "a marker");
        c.add(new ToolSite("Rule 'x' was violated (Foo.java)", "a/Foo.java", 12), "arch");
        c.population(42);
        Object json = MiniJson.parse(line(c).toJson());
        List<?> v = (List<?>) requireNonNull(MiniJson.get(json, "violations"));
        assertThat(v).hasSize(3);
        assertThat(MiniJson.str(v.get(0), "fingerprint"))
                .isEqualTo("a.Esc#write()V -> java.lang.String#replace(CC)Ljava/lang/String;");
        assertThat(MiniJson.str(v.get(0), "file")).isEqualTo("a/Esc.java");
        assertThat(((Number) requireNonNull(MiniJson.get(v.get(0), "line"))).intValue())
                .isEqualTo(7);
        assertThat(MiniJson.str(v.get(0), "root")).isEqualTo("source");
        assertThat(MiniJson.str(v.get(0), "detail")).isEqualTo("an escaper");
        assertThat(MiniJson.str(v.get(1), "fingerprint")).isEqualTo("docs/x.md | TODO");
        assertThat(MiniJson.str(v.get(1), "root")).isEqualTo("workspace");
        assertThat(MiniJson.str(v.get(2), "root")).isEqualTo("workspace");
        assertThat(((Number) requireNonNull(MiniJson.get(v.get(2), "line"))).intValue())
                .isEqualTo(12);
        assertThat(((Number) requireNonNull(MiniJson.get(json, "population"))).intValue())
                .isEqualTo(42);
    }

    @Test
    void a_site_without_a_file_omits_the_key() {
        Report.Collector c = new Report.Collector();
        c.add(new ToolSite("Rule 'y' was violated", null, 0), "arch");
        Object json = MiniJson.parse(line(c).toJson());
        Object site = ((List<?>) requireNonNull(MiniJson.get(json, "violations"))).get(0);
        assertThat(MiniJson.get(site, "file")).isNull();
        assertThat(MiniJson.get(site, "value")).isNull();
    }

    @Test
    void a_metric_carries_its_value_at_the_workspace_root() {
        Report.Collector c = new Report.Collector();
        c.metric(new MetricSite("shared/host/Big.java", 812.0, "shared/host/Big.java"), "812 code lines (cap 800)");
        Object site = ((List<?>) requireNonNull(MiniJson.get(MiniJson.parse(line(c).toJson()), "violations"))).get(0);
        assertThat(MiniJson.str(site, "fingerprint")).isEqualTo("shared/host/Big.java");
        assertThat(((Number) requireNonNull(MiniJson.get(site, "value"))).doubleValue())
                .isEqualTo(812.0);
        assertThat(((Number) requireNonNull(MiniJson.get(site, "line"))).intValue())
                .isZero();
        assertThat(MiniJson.str(site, "root")).isEqualTo("workspace");
    }

    @Test
    void a_throwing_guard_reports_the_first_lines_of_its_stack() {
        Report.Collector c = new Report.Collector();
        c.threw(new IllegalStateException("kaboom"));
        Object json = MiniJson.parse(line(c).toJson());
        assertThat(MiniJson.str(json, "outcome")).isEqualTo("threw");
        String error = requireNonNull(MiniJson.str(json, "error"));
        assertThat(error).startsWith("java.lang.IllegalStateException: kaboom");
        assertThat(error.split("\n").length).isLessThanOrEqualTo(6);
    }

    @Test
    void a_missing_owner_is_its_own_outcome() {
        Report.Collector c = new Report.Collector();
        c.ownerMissing("owner a.Missing is not in the facts in scope");
        Object json = MiniJson.parse(line(c).toJson());
        assertThat(MiniJson.str(json, "outcome")).isEqualTo("owner-missing");
        assertThat(MiniJson.str(json, "error")).isEqualTo("owner a.Missing is not in the facts in scope");
    }

    @Test
    void a_skipped_run_is_its_own_outcome_with_the_reason() {
        Report.Collector c = new Report.Collector();
        c.skipped("shellcheck: not installed");
        Object json = MiniJson.parse(line(c).toJson());
        assertThat(MiniJson.str(json, "outcome")).isEqualTo("skipped");
        assertThat(MiniJson.str(json, "error")).isEqualTo("shellcheck: not installed");
    }

    @Test
    void a_fixture_run_names_its_fixture_and_quotes_are_escaped() {
        Report.Collector c = new Report.Collector();
        c.add(new TextSite("a/B.java", 1, "say \"hi\""), "quoted \"detail\"");
        Report.Line l = new Report.Line("id", "w \"q\"", "", "s", Scope.MODULE, List.of(), "Bad.java", List.of(), c);
        Object json = MiniJson.parse(l.toJson());
        assertThat(MiniJson.str(json, "fixture")).isEqualTo("Bad.java");
        assertThat(MiniJson.str(json, "why")).isEqualTo("w \"q\"");
        assertThat(MiniJson.str(json, "scope")).isEqualTo("module");
        Object site = ((List<?>) requireNonNull(MiniJson.get(json, "violations"))).get(0);
        assertThat(MiniJson.str(site, "detail")).isEqualTo("quoted \"detail\"");
        assertThat(MiniJson.str(site, "fingerprint")).isEqualTo("a/B.java | say \"hi\"");
    }

    @Test
    void append_creates_the_directory_and_adds_one_line_per_call(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("target/guard/report.jsonl");
        Report.append(file, line(new Report.Collector()));
        Report.Collector second = new Report.Collector();
        second.population(1);
        Report.append(file, line(second));
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        assertThat(MiniJson.get(MiniJson.parse(lines.get(0)), "population")).isNull();
        assertThat(((Number) requireNonNull(MiniJson.get(MiniJson.parse(lines.get(1)), "population"))).intValue())
                .isEqualTo(1);
    }
}
