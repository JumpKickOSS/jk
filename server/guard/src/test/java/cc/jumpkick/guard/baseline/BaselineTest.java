// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineTest {

    private static final String SITE =
            "cc.jumpkick.publish.Gpg#sign([B)[B -> java.security.MessageDigest#getInstance(**)";

    @Test
    void render_is_deterministic_and_round_trips(@TempDir Path dir) throws IOException {
        Baseline b = Baseline.EMPTY
                .with(
                        "one-digest-surface",
                        RuleBaseline.of(
                                Map.of("classes", 1266L), List.of(new Entry.Site(SITE, "PGP needs SHA-1 by spec"))))
                .with(
                        "file-size",
                        RuleBaseline.of(
                                Map.of("files", 2318L),
                                List.of(
                                        new Entry.Metric(
                                                "server/engine/src/main/java/cc/jumpkick/engine/BuildGraph.java",
                                                1064,
                                                "one owner for the graph"),
                                        new Entry.Metric("a/B.java", 900.5, "half"))));
        Path f = dir.resolve("jk-guards-baseline.toml");
        BaselineFile.write(f, b);
        String text = Files.readString(f);
        assertThat(text)
                .contains(
                        "[file-size]\npopulation = { files = 2318 }\n[[file-size.entries]]\nunit   = \"a/B.java\"\nvalue  = 900.5\n");
        assertThat(text).contains("value  = 1064\n");
        assertThat(text.indexOf("[file-size]")).as("ids sorted").isLessThan(text.indexOf("[one-digest-surface]"));
        Baseline back = BaselineFile.read(f);
        assertThat(back).isEqualTo(b);
        BaselineFile.write(f, back);
        assertThat(Files.readString(f)).isEqualTo(text);
    }

    @Test
    void module_lane_slices_round_trip_and_reconcile_apart(@TempDir Path dir) throws IOException {
        RuleBaseline rb = RuleBaseline.EMPTY
                .withLane("shared/host", Map.of("classes", 50L), List.of(new Entry.Site("h.H#f()V -> x", "host")))
                .withLane("clients/cli", Map.of("classes", 200L), List.of(new Entry.Site("c.C#g()V -> x", "cli")));
        Path f = dir.resolve("jk-guards-baseline.toml");
        BaselineFile.write(f, Baseline.EMPTY.with("walks", rb));
        String text = Files.readString(f);
        assertThat(text)
                .contains(
                        "[walks.populations]\n\"clients/cli\" = { classes = 200 }\n\"shared/host\" = { classes = 50 }\n")
                .contains("[[walks.entries]]\nin     = \"clients/cli\"\nat     = \"c.C#g()V -> x\"");
        assertThat(BaselineFile.read(f)).isEqualTo(Baseline.EMPTY.with("walks", rb));

        // The host lane sees only its slice: cli's entry is neither matched nor stale, and a shrink
        // is judged against the host population alone.
        Reconciliation host = Reconciliation.of("walks", rb, List.of(), Map.of("classes", 50L), "shared/host");
        assertThat(host.stale()).extracting(Entry::key).containsExactly("h.H#f()V -> x");
        assertThat(host.tightened().entries("clients/cli")).hasSize(1);
        assertThat(host.tightened().population("clients/cli")).containsEntry("classes", 200L);
        Reconciliation shrunk = Reconciliation.of("walks", rb, List.of(), Map.of("classes", 30L), "shared/host");
        assertThat(shrunk.scopeShrunk()).isEqualTo("classes: 50 → 30");
        RuleBaseline frozen = Reconciliation.of(
                        "walks",
                        rb,
                        List.of(Observation.site("h.H#k()V -> x", null, 0, "")),
                        Map.of("classes", 55L),
                        "shared/host")
                .frozen("agreed");
        assertThat(frozen.entries("shared/host")).extracting(Entry::key).containsExactly("h.H#k()V -> x");
        assertThat(frozen.population("shared/host")).containsEntry("classes", 55L);
        assertThat(frozen.entries("clients/cli")).hasSize(1);
    }

    @Test
    void an_empty_baseline_deletes_the_file(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("jk-guards-baseline.toml");
        BaselineFile.write(f, Baseline.EMPTY.with("x", RuleBaseline.of(Map.of(), List.of(new Entry.Site("a", "r")))));
        assertThat(f).exists();
        BaselineFile.write(f, Baseline.EMPTY);
        assertThat(f).doesNotExist();
    }

    @Test
    void a_hand_edited_broken_file_is_refused_with_the_sanctioned_path() {
        assertThatThrownBy(() -> BaselineFile.parse("[x]\nentries = [\n", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("never edit the baseline by hand");
    }

    @Test
    void sites_new_baselined_and_stale() {
        RuleBaseline before = RuleBaseline.of(
                Map.of("classes", 100L), List.of(new Entry.Site("a", "ra"), new Entry.Site("gone", "rg")));
        Reconciliation r = Reconciliation.of(
                "x",
                before,
                List.of(Observation.site("a", null, 0, ""), Observation.site("b", null, 0, "")),
                Map.of("classes", 100L));
        assertThat(r.fresh()).extracting(Observation::key).containsExactly("b");
        assertThat(r.baselined()).extracting(Observation::key).containsExactly("a");
        assertThat(r.stale()).extracting(Entry::key).containsExactly("gone");
        assertThat(r.tightened().entries()).extracting(Entry::key).containsExactly("a");
        assertThat(r.tighteningNeeded()).isTrue();
        assertThat(r.red()).isTrue();
        RuleBaseline frozen = r.frozen("because");
        assertThat(frozen.entries()).extracting(Entry::key).containsExactly("a", "b");
        assertThat(frozen.entries().get(1).reason()).isEqualTo("because");
    }

    @Test
    void observations_sharing_a_fingerprint_are_one_site_and_one_entry() {
        RuleBaseline before = RuleBaseline.of(Map.of("classes", 10L), List.of(new Entry.Site("a", "ra")));
        List<Observation> observed = List.of(
                Observation.site("a", "A.java", 1, "first"),
                Observation.site("a", "A.java", 9, "lambda folded onto its method"),
                Observation.site("b", null, 0, ""),
                Observation.site("b", null, 0, ""));
        Reconciliation r = Reconciliation.of("x", before, observed, Map.of("classes", 10L));
        assertThat(r.baselined()).extracting(Observation::key).containsExactly("a");
        assertThat(r.fresh()).extracting(Observation::key).containsExactly("b");
        assertThat(r.tightened().entries()).extracting(Entry::key).containsExactly("a");
        assertThat(r.tighteningNeeded())
                .as("the same entry seen twice is not a change")
                .isFalse();
        assertThat(r.frozen("because").entries()).extracting(Entry::key).containsExactly("a", "b");
    }

    @Test
    void metrics_tighten_on_shrink_and_are_red_on_growth() {
        RuleBaseline before = RuleBaseline.of(Map.of(), List.of(new Entry.Metric("F.java", 1000, "r")));
        Reconciliation shrink = Reconciliation.of(
                "m", before, List.of(Observation.metric("F.java", 900, null, "")), Map.of("files", 1L));
        assertThat(shrink.red()).isFalse();
        assertThat(shrink.tighteningNeeded()).isTrue();
        assertThat(((Entry.Metric) shrink.tightened().entries().get(0)).value()).isEqualTo(900);
        Reconciliation same = Reconciliation.of(
                "m", shrink.tightened(), List.of(Observation.metric("F.java", 900, null, "")), Map.of("files", 1L));
        assertThat(same.tighteningNeeded()).isFalse();
        Reconciliation grow = Reconciliation.of(
                "m", before, List.of(Observation.metric("F.java", 1001, null, "")), Map.of("files", 1L));
        assertThat(grow.red()).isTrue();
        assertThat(grow.fresh()).hasSize(1);
        assertThat(grow.tightened().entries())
                .as("the old line stays until frozen")
                .hasSize(1);
    }

    @Test
    void a_floor_metric_is_red_below_its_entry_and_tightens_upward_beyond_the_band() {
        Tolerance floor = new Tolerance(true, 0.5);
        RuleBaseline before = RuleBaseline.of(Map.of("units", 1L), List.of(new Entry.Metric("shared/host", 80.0, "r")));
        Reconciliation held = Reconciliation.of(
                "c",
                before,
                List.of(Observation.metric("shared/host", 79.7, null, "")),
                Map.of("units", 1L),
                "",
                floor);
        assertThat(held.red()).as("within the band the entry holds").isFalse();
        assertThat(held.tighteningNeeded()).isFalse();
        Reconciliation dropped = Reconciliation.of(
                "c",
                before,
                List.of(Observation.metric("shared/host", 79.4, null, "")),
                Map.of("units", 1L),
                "",
                floor);
        assertThat(dropped.red()).as("past the band below is a regression").isTrue();
        assertThat(dropped.tightened().entries()).hasSize(1);
        Reconciliation rose = Reconciliation.of(
                "c",
                before,
                List.of(Observation.metric("shared/host", 80.6, null, "")),
                Map.of("units", 1L),
                "",
                floor);
        assertThat(rose.red()).isFalse();
        assertThat(rose.tighteningNeeded())
                .as("past the band above banks the improvement")
                .isTrue();
        assertThat(((Entry.Metric) rose.tightened().entries().get(0)).value()).isEqualTo(80.6);
    }

    @Test
    void a_cap_with_no_band_reads_exactly_as_before() {
        assertThat(Tolerance.CAP.worse(1001, 1000)).isTrue();
        assertThat(Tolerance.CAP.better(999, 1000)).isTrue();
        assertThat(Tolerance.CAP.worse(1000, 1000)).isFalse();
        assertThat(Tolerance.CAP.better(1000, 1000)).isFalse();
    }

    @Test
    void scope_shrunk_below_eighty_percent_is_red_and_never_tightens() {
        RuleBaseline before = RuleBaseline.of(Map.of("classes", 100L), List.of(new Entry.Site("a", "r")));
        Reconciliation r = Reconciliation.of("x", before, List.of(), Map.of("classes", 70L));
        assertThat(r.scopeShrunk()).isEqualTo("classes: 100 → 70");
        assertThat(r.red()).isTrue();
        assertThat(r.tighteningNeeded()).isFalse();
        Reconciliation ok =
                Reconciliation.of("x", before, List.of(Observation.site("a", null, 0, "")), Map.of("classes", 80L));
        assertThat(ok.scopeShrunk()).isNull();
    }

    @Test
    void orphans_and_ci_policy() {
        Baseline b = Baseline.EMPTY
                .with("kept", RuleBaseline.of(Map.of(), List.of(new Entry.Site("a", "r"))))
                .with("removed", RuleBaseline.of(Map.of(), List.of(new Entry.Site("b", "r"))));
        assertThat(b.orphans(Set.of("kept"))).containsExactly("removed");
        assertThat(b.without("removed").rules()).containsOnlyKeys("kept");
        assertThat(Baselines.ciMode(k -> "true")).isTrue();
        assertThat(Baselines.ciMode(k -> "false")).isFalse();
        assertThat(Baselines.ciMode(k -> null)).isFalse();
        assertThat(Baselines.freezeRefusal("", false)).contains("--reason");
        assertThat(Baselines.freezeRefusal("why", true)).contains("CI");
        assertThat(Baselines.freezeRefusal("why", false)).isNull();
    }
}
