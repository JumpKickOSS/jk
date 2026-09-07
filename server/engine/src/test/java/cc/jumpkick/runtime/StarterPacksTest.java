// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.guard.eval.FixtureCheck;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every shipped rule pack under {@code packs/}: its fragment loads clean and within the token budget
 * as a root rules file, and every fixture-bearing rule bites — {@code Bad} fires, {@code Ok} is
 * quiet — with nothing but the fixture's own stub types on the classpath.
 */
class StarterPacksTest {

    static Stream<String> packs() {
        return Stream.of("spring", "quarkus", "android", "library", "monorepo");
    }

    @ParameterizedTest
    @MethodSource("packs")
    void the_pack_loads_clean_and_every_fixture_bites(String pack, @TempDir Path tmp) throws Exception {
        Path resources = RepoRoot.dir(StarterPacksTest.class, "packs/" + pack + "/src/main/resources");
        Path root = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(
                root.resolve("jk.toml"), "group = \"t\"\nname = \"consumer\"\nversion = \"0.0.1\"\njdk = 25\n");
        Files.copy(resources.resolve(GuardsPresence.RULES_FILE), root.resolve(GuardsPresence.RULES_FILE));
        copyTree(resources.resolve("guard-fixtures"), root.resolve("guard-fixtures"));

        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.problems()).as("load errors and token-budget warnings").isEmpty();
        assertThat(load.rules().ids()).isNotEmpty();
        for (Rule r : load.rules().rules().values()) {
            assertThat(r.why()).as(r.id() + " why").isNotBlank();
        }
        List<FixtureCheck.Case> cases = FixtureCheck.cases(root, load.rules(), Map.of());
        boolean bans = load.rules().rules().values().stream().anyMatch(r -> r.kind() == Kind.FORBID);
        if (bans)
            assertThat(cases)
                    .as("a pack that bans something proves it with fixtures")
                    .isNotEmpty();
        GuardFixtures.Result r = GuardFixtures.run(root, new Cas(tmp.resolve("store")));
        assertThat(r.loadErrors()).isEmpty();
        assertThat(r.verdicts()).as(r.text()).allSatisfy(v -> assertThat(v.outcome())
                .as(v.id() + ": " + v.note())
                .isEqualTo("bites"));
        assertThat(r.verdicts())
                .extracting(FixtureCheck.Verdict::id)
                .containsExactlyInAnyOrderElementsOf(
                        cases.stream().map(FixtureCheck.Case::id).toList());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }
}
