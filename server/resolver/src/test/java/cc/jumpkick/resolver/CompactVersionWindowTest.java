// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * the compact candidate window must always offer a stable version when one exists.
 *
 * <p>The cap exists so PubGrub does not thrash on long histories, and {@link
 * cc.jumpkick.resolver.pubgrub.AllowedSet#choosePreferred} already prefers stable over
 * pre-release. But the two together had a hole: take simply the highest four and a project with four
 * or more pre-releases above its latest release leaves the stable preference nothing stable to pick.
 * jackson-annotations is exactly that shape, so a caret on 2.22 resolved to 3.0-rc5.
 */
class CompactVersionWindowTest {

    /** jackson-annotations as published, highest-first — five 3.0 RCs sitting above a stable 2.22. */
    private static List<String> jacksonHighestFirst() {
        return new ArrayList<>(List.of(
                "3.0-rc5",
                "3.0-rc4",
                "3.0-rc3",
                "3.0-rc2",
                "3.0-rc1",
                "2.22",
                "2.21",
                "2.20",
                "2.20-rc1",
                "2.19.4",
                "2.19.3",
                "2.19.2"));
    }

    @Test
    void the_window_holds_stable_releases_even_when_pre_releases_are_higher() {
        List<String> window = MavenPackageSource.compactVersionCandidates(jacksonHighestFirst());

        assertThat(window).containsExactly("2.22", "2.21", "2.20", "2.19.4");
        assertThat(window).noneMatch(v -> v.contains("rc"));
    }

    @Test
    void the_window_stays_highest_first() {
        // AllowedSet walks the universe in index order and infers a front-loaded pin by comparison,
        // so the ordering is load-bearing, not cosmetic.
        List<String> window = MavenPackageSource.compactVersionCandidates(jacksonHighestFirst());

        for (int i = 1; i < window.size(); i++) {
            assertThat(Versions.compare(window.get(i - 1), window.get(i)))
                    .as(window.get(i - 1) + " > " + window.get(i))
                    .isGreaterThan(0);
        }
    }

    @Test
    void a_project_with_only_pre_releases_still_has_candidates() {
        // Never shipped a stable: filtering to stable-only would make it unresolvable, so
        // pre-releases take the leftover slots.
        List<String> onlyRcs = new ArrayList<>(List.of("1.0-rc5", "1.0-rc4", "1.0-rc3", "1.0-rc2", "1.0-rc1"));

        assertThat(MavenPackageSource.compactVersionCandidates(onlyRcs))
                .containsExactly("1.0-rc5", "1.0-rc4", "1.0-rc3", "1.0-rc2");
    }

    @Test
    void a_short_history_is_returned_untouched() {
        // Under the cap the whole history is the universe, so the downstream stable preference can
        // already see a stable candidate — there is nothing to protect against.
        List<String> shortList = new ArrayList<>(List.of("3.0-rc1", "2.0", "1.0"));

        assertThat(MavenPackageSource.compactVersionCandidates(shortList)).containsExactly("3.0-rc1", "2.0", "1.0");
    }

    @Test
    void a_pre_release_pin_at_the_front_survives_and_keeps_a_higher_version_beside_it() {
        // an explicit lock/BOM pin outranks this policy, including a pre-release pin.
        // AllowedSet detects "this front is a pin, take it unconditionally" by finding some HIGHER
        // version in the universe — so the natural max has to stay in the window or the pin loses to
        // a lower stable.
        List<String> pinned = jacksonHighestFirst();
        MavenPackageSource.preferFirst(pinned, "3.0-rc3");

        List<String> window = MavenPackageSource.compactVersionCandidates(pinned);

        assertThat(window).startsWith("3.0-rc3");
        assertThat(window).contains("3.0-rc5");
        assertThat(window.stream().anyMatch(v -> Versions.compare(v, "3.0-rc3") > 0))
                .as("a higher version must remain so the pin is recognised as front-loaded")
                .isTrue();
    }

    @Test
    void a_stable_pin_at_the_front_also_survives() {
        List<String> pinned = jacksonHighestFirst();
        MavenPackageSource.preferFirst(pinned, "2.20");

        List<String> window = MavenPackageSource.compactVersionCandidates(pinned);

        assertThat(window).startsWith("2.20").contains("3.0-rc5");
    }

    @Test
    void the_snapshot_window_keeps_the_newest_whatever_it_is() {
        // `snapshot` is the sanctioned opt-in, so no stability narrowing applies to it.
        assertThat(MavenPackageSource.compactHighest(jacksonHighestFirst()))
                .containsExactly("3.0-rc5", "3.0-rc4", "3.0-rc3", "3.0-rc2");
    }
}
