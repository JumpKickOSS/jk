// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionUniverseTest {

    @Test
    void project_exact_missing_version_is_empty() {
        VersionUniverse u = VersionUniverse.of("widget", List.of("2.0", "1.0"));
        assertThat(u.project(VersionSet.exact("9.9.9")).isEmpty()).isTrue();
    }

    @Test
    void project_at_least_selects_matching_indices() {
        VersionUniverse u = VersionUniverse.of("widget", List.of("3.0", "2.0", "1.0"));
        AllowedSet a = u.project(VersionSet.atLeast("2.0", true));
        assertThat(a.containsVersion("3.0")).isTrue();
        assertThat(a.containsVersion("2.0")).isTrue();
        assertThat(a.containsVersion("1.0")).isFalse();
        assertThat(a.choosePreferred()).isEqualTo("3.0");
    }

    @Test
    void project_all_and_empty() {
        VersionUniverse u = VersionUniverse.of("widget", List.of("1.0", "0.9"));
        assertThat(u.project(VersionSet.ALL).size()).isEqualTo(2);
        assertThat(u.project(VersionSet.EMPTY).isEmpty()).isTrue();
    }

    @Test
    void soft_prefer_order_choosePreferred_takes_index_zero_when_allowed() {
        // Prefer pin 1.5 at front even though 2.0 is higher — same as PackageSource order.
        VersionUniverse u = VersionUniverse.of("widget", List.of("1.5", "2.0", "1.0"));
        AllowedSet a = u.project(VersionSet.atLeast("1.0", true));
        assertThat(a.choosePreferred()).isEqualTo("1.5");
    }

    @Test
    void intersect_and_subset() {
        VersionUniverse u = VersionUniverse.of("widget", List.of("3.0", "2.0", "1.0"));
        AllowedSet high = u.project(VersionSet.atLeast("2.0", true));
        AllowedSet low = u.project(VersionSet.lessThan("2.5", false));
        AllowedSet mid = high.intersect(low);
        assertThat(mid.containsVersion("2.0")).isTrue();
        assertThat(mid.containsVersion("3.0")).isFalse();
        assertThat(mid.subsetOf(high)).isTrue();
        assertThat(high.subsetOf(mid)).isFalse();
    }

    @Test
    void choosePreferred_skips_prerelease_when_stable_exists() {
        VersionUniverse u = VersionUniverse.of("widget", List.of("3.0.0-RC1", "2.0.0", "1.0.0"));
        AllowedSet a = u.all();
        assertThat(a.choosePreferred()).isEqualTo("2.0.0");
    }

    @Test
    void toVersionSet_round_trips_membership() {
        VersionUniverse u = VersionUniverse.of("widget", List.of("3.0", "2.0", "1.0"));
        AllowedSet a = u.project(VersionSet.atLeast("2.0", true));
        VersionSet vs = a.toVersionSet();
        assertThat(vs.contains("3.0")).isTrue();
        assertThat(vs.contains("2.0")).isTrue();
        assertThat(vs.contains("1.0")).isFalse();
    }

    @Test
    void empty_universe() {
        VersionUniverse u = VersionUniverse.of("missing", List.of());
        assertThat(u.project(VersionSet.ALL).isEmpty()).isTrue();
        assertThat(u.all().choosePreferred()).isNull();
    }

    @Test
    void many_exclusions_via_intersect_stay_fast_and_correct() {
        List<String> versions = new ArrayList<>();
        for (int i = 200; i >= 1; i--) {
            versions.add(i + ".0.0");
        }
        VersionUniverse u = VersionUniverse.of("big", versions);
        AllowedSet a = u.all();
        for (int i = 1; i <= 100; i++) {
            a = a.intersect(u.project(VersionSet.exact(i + ".0.0").complement()));
        }
        assertThat(a.size()).isEqualTo(100);
        assertThat(a.containsVersion("150.0.0")).isTrue();
        assertThat(a.containsVersion("50.0.0")).isFalse();
        assertThat(a.choosePreferred()).isEqualTo("200.0.0");
    }
}
