// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.extract.fixture.FixtureBytes;
import cc.jumpkick.guard.extract.fixture.Sample;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FreezerTest {

    private static final String RULES = """
            [guards.one-digest]
            kind       = "forbid"
            signatures = ["java.security.MessageDigest#getInstance(**)"]
            instead    = "Hashing"
            why        = "one algorithm table"
            """;

    private static Path project(Path dir) throws IOException {
        Path p = Files.createDirectories(dir.resolve("p"));
        Files.writeString(p.resolve(ManifestPaths.MANIFEST), "name = \"p\"\ngroup = \"g\"\nversion = \"1\"\n");
        Files.writeString(p.resolve(GuardsPresence.RULES_FILE), RULES);
        // The last build's output: the fixture class in the module's classes dir.
        BuildLayout layout = BuildLayout.of(p, JkBuildParser.parse(p.resolve(ManifestPaths.MANIFEST)));
        Path pkg = Files.createDirectories(layout.classesDir().resolve("cc/jumpkick/guard/extract/fixture"));
        Files.write(pkg.resolve("Sample.class"), FixtureBytes.of(Sample.class));
        return p;
    }

    @Test
    void freeze_accepts_the_current_new_sites_with_the_reason(@TempDir Path dir) throws Exception {
        Path p = project(dir);
        Freezer.Result r = Freezer.freeze(p, "one-digest", "PGP needs SHA-1 by spec", false, false);
        assertThat(r.error()).isNull();
        assertThat(r.accepted()).isEqualTo(1);
        Baseline b = BaselineFile.read(GuardsPresence.baselineFile(p));
        assertThat(b.of("one-digest").entries()).singleElement().satisfies(e -> {
            assertThat(e.key()).startsWith("cc.jumpkick.guard.extract.fixture.Sample#digest()");
            assertThat(e.reason()).isEqualTo("PGP needs SHA-1 by spec");
        });
        assertThat(b.of("one-digest").population()).containsKey("classes");
        // A second freeze has nothing new.
        assertThat(Freezer.freeze(p, "one-digest", "again", false, false).accepted())
                .isZero();
    }

    @Test
    void refusals_no_reason_unknown_rule_and_retire(@TempDir Path dir) throws Exception {
        Path p = project(dir);
        assertThat(Freezer.freeze(p, "one-digest", "", false, false).error()).contains("--reason");
        assertThat(Freezer.freeze(p, "nope", "r", false, false).error())
                .contains("no rule `nope`")
                .contains("one-digest");
        assertThat(Freezer.freeze(p, "one-digest", null, true, false).error()).contains("still declared");
        // Retire a rule that is gone from the file but still in the baseline.
        BaselineFile.write(
                GuardsPresence.baselineFile(p),
                Baseline.EMPTY.with("gone", RuleBaseline.of(Map.of(), List.of(new Entry.Site("x", "r")))));
        Freezer.Result retired = Freezer.freeze(p, "gone", null, true, false);
        assertThat(retired.error()).isNull();
        assertThat(retired.accepted()).isEqualTo(1);
        assertThat(BaselineFile.read(GuardsPresence.baselineFile(p)).rules()).isEmpty();
    }

    /** A shrink is a question a plain freeze refuses to answer; --accept-scope is the answer, recorded with its reason. */
    @Test
    void a_shrunk_population_is_refused_until_accept_scope_records_it_with_the_reason(@TempDir Path dir)
            throws Exception {
        Path p = project(dir);
        // The baseline remembers a population the tree no longer has: ten classes, and the fixture holds one.
        Path file = GuardsPresence.baselineFile(p);
        BaselineFile.write(file, Baseline.EMPTY.with("one-digest", RuleBaseline.of(Map.of("classes", 10L), List.of())));
        Freezer.Result plain = Freezer.freeze(p, "one-digest", "sites", false, false);
        assertThat(plain.error()).contains("classes: 10 → ").contains("jk guard freeze one-digest --accept-scope");
        assertThat(BaselineFile.read(file).of("one-digest").population())
                .as("a refused freeze writes nothing")
                .containsEntry("classes", 10L);

        Freezer.Result accepted = Freezer.freeze(p, "one-digest", "the sample corpus is one class", false, true);
        assertThat(accepted.error()).isNull();
        assertThat(accepted.rebased()).isEqualTo(1);
        assertThat(accepted.accepted())
                .as("the fresh site rides the same freeze")
                .isEqualTo(1);
        RuleBaseline after = BaselineFile.read(file).of("one-digest");
        assertThat(after.population()).containsKey("classes").doesNotContainEntry("classes", 10L);
        assertThat(after.scopeReason("")).isEqualTo("the sample corpus is one class");
        assertThat(Files.readString(file)).contains("\nscope-reason = \"the sample corpus is one class\"\n");

        // Nothing shrank and nothing is new: an accept-scope freeze has nothing to record and says so.
        Freezer.Result nothing = Freezer.freeze(p, "one-digest", "again", false, true);
        assertThat(nothing.error()).isNull();
        assertThat(nothing.rebased()).isZero();
        assertThat(nothing.accepted()).isZero();
    }

    @Test
    void retire_refuses_a_guard_still_declared_in_source(@TempDir Path dir) throws Exception {
        Path p = project(dir);
        Path src = Files.createDirectories(p.resolve("src/guard/java/house"));
        Files.writeString(src.resolve("House.java"), """
                package house;
                import cc.jumpkick.guard.api.Guard;
                import cc.jumpkick.guard.api.GuardSuite;
                import cc.jumpkick.guard.api.Scope;
                @GuardSuite(scope = Scope.MODULE)
                final class House {
                  @Guard(id = "still-here", why = "live")
                  void stillHere() {}
                }
                """);
        BaselineFile.write(
                GuardsPresence.baselineFile(p),
                Baseline.EMPTY.with("still-here", RuleBaseline.of(Map.of(), List.of(new Entry.Site("x", "r")))));
        assertThat(Freezer.freeze(p, "still-here", null, true, false).error()).contains("still declared");
    }
}
