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
                        new RuleBaseline(
                                Map.of("classes", 1266L), List.of(new Entry.Site(SITE, "PGP needs SHA-1 by spec"))))
                .with(
                        "file-size",
                        new RuleBaseline(
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
    void an_empty_baseline_deletes_the_file(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("jk-guards-baseline.toml");
        BaselineFile.write(f, Baseline.EMPTY.with("x", new RuleBaseline(Map.of(), List.of(new Entry.Site("a", "r")))));
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
        RuleBaseline before = new RuleBaseline(
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
    void metrics_tighten_on_shrink_and_are_red_on_growth() {
        RuleBaseline before = new RuleBaseline(Map.of(), List.of(new Entry.Metric("F.java", 1000, "r")));
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
    void scope_shrunk_below_eighty_percent_is_red_and_never_tightens() {
        RuleBaseline before = new RuleBaseline(Map.of("classes", 100L), List.of(new Entry.Site("a", "r")));
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
                .with("kept", new RuleBaseline(Map.of(), List.of(new Entry.Site("a", "r"))))
                .with("removed", new RuleBaseline(Map.of(), List.of(new Entry.Site("b", "r"))));
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
