// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.ToolchainSpec;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LockPinMatchTest {

    @Test
    void empty_when_nothing_meets_the_major_floor() {
        JdkHit only21 = hit("21.0.5", JdkVendor.TEMURIN);
        assertThat(LockPinMatch.choose(List.of(only21), "temurin", "25.0.4")).isEmpty();
        assertThat(LockPinMatch.meetsFloor("21.0.5", "25.0.4")).isFalse();
    }

    @Test
    void exact_vendor_and_version_wins() {
        JdkHit exact = hit("25.0.4", JdkVendor.TEMURIN);
        JdkHit newer = hit("25.0.5", JdkVendor.TEMURIN);
        JdkHit other = hit("25.0.4", JdkVendor.CORRETTO);
        assertThat(LockPinMatch.choose(List.of(newer, other, exact), "temurin", "25.0.4"))
                .contains(exact);
    }

    @Test
    void same_vendor_newer_patch_when_exact_is_missing() {
        JdkHit newer = hit("25.0.5", JdkVendor.TEMURIN);
        JdkHit other = hit("25.0.4", JdkVendor.CORRETTO);
        assertThat(LockPinMatch.choose(List.of(other, newer), "temurin", "25.0.4"))
                .contains(newer);
    }

    @Test
    void an_older_patch_on_the_same_major_still_clears_the_floor() {
        // The floor is the major and only the major: a suggestion records what built the lock, it
        // does not hold a later build to that patch. Requiring 25.0.4 exactly is what = is for.
        JdkHit older = hit("25.0.3", JdkVendor.TEMURIN);
        assertThat(LockPinMatch.meetsFloor("25.0.3", "25.0.4")).isTrue();
        assertThat(LockPinMatch.choose(List.of(older), "temurin", "25.0.4")).contains(older);
        assertThat(LockPinMatch.meetsFloor("21.0.9", "25.0.4")).isFalse();
    }

    @Test
    void a_required_version_admits_nothing_else() {
        JdkHit older = hit("25.0.3", JdkVendor.TEMURIN);
        JdkHit exact = hit("25.0.4", JdkVendor.TEMURIN);
        JdkHit newer = hit("25.1.0", JdkVendor.TEMURIN);
        Lockfile.JdkPin pin = new Lockfile.JdkPin("", "", "", "25.0.4");
        assertThat(LockPinMatch.choose(List.of(older, newer), pin)).isEmpty();
        assertThat(LockPinMatch.choose(List.of(older, exact, newer), pin)).contains(exact);
    }

    @Test
    void a_required_vendor_admits_no_other_vendor() {
        JdkHit corretto = hit("25.0.9", JdkVendor.CORRETTO);
        JdkHit temurin = hit("25.0.4", JdkVendor.TEMURIN);
        Lockfile.JdkPin pin = new Lockfile.JdkPin("", "25", "temurin", "");
        assertThat(LockPinMatch.choose(List.of(corretto), pin)).isEmpty();
        assertThat(LockPinMatch.choose(List.of(corretto, temurin), pin)).contains(temurin);
    }

    @Test
    void any_vendor_at_the_floor_when_locked_vendor_is_absent() {
        JdkHit corretto = hit("25.0.4", JdkVendor.CORRETTO);
        assertThat(LockPinMatch.choose(List.of(corretto), "temurin", "25.0.4")).contains(corretto);
    }

    @Test
    void same_vendor_at_the_floor_beats_a_newer_major_of_another_vendor() {
        JdkHit v25 = hit("25.0.9", JdkVendor.TEMURIN);
        JdkHit v26 = hit("26.0.1", JdkVendor.CORRETTO);
        assertThat(LockPinMatch.choose(List.of(v25, v26), "temurin", "25.0.4")).contains(v25);
    }

    @Test
    void the_named_major_wins_when_the_locked_vendor_is_absent() {
        // 26 clears a 25 floor, and 25 is the major the lock named: with both on disk the build
        // that reproduces the record is the one to fork, whatever its vendor.
        JdkHit v25 = hit("25.0.9", JdkVendor.CORRETTO);
        JdkHit v26 = hit("26.0.1", JdkVendor.LIBERICA);
        assertThat(LockPinMatch.choose(List.of(v25, v26), "temurin", "25.0.4")).contains(v25);
        assertThat(LockPinMatch.meetsFloor("26.0.1", "25.0.4")).isTrue();
        assertThat(LockPinMatch.choose(List.of(v26), "temurin", "25.0.4"))
                .as("with no 25 anywhere the floor is what decides")
                .contains(v26);
    }

    /**
     * {@code jdk = 17} in a manifest is a floor, so a 25 on its own satisfies it — but a 17 that
     * is installed beside the 25 is the JDK the project named, and it ranks above the newer
     * install of the same vendor; {@code JdkFloorTest} proves the forked JVM agrees.
     */
    @Test
    void the_named_major_beats_a_newer_install_of_the_same_vendor() {
        JdkHit v17 = hit("17.0.20.1", JdkVendor.TEMURIN);
        JdkHit v25 = hit("25.0.4.1", JdkVendor.TEMURIN);
        assertThat(LockPinMatch.choose(List.of(v25, v17), "temurin", "17")).contains(v17);
        assertThat(LockPinMatch.choose(List.of(v25), "temurin", "17"))
                .as("nothing at 17: the floor admits the 25")
                .contains(v25);
    }

    @Test
    void install_spec_is_vendor_and_major() {
        assertThat(LockPinMatch.installSpec("temurin", "25.0.4.1")).isEqualTo("temurin-25");
        assertThat(LockPinMatch.installSpec("", "25.0.4")).isEqualTo("25");
    }

    @Test
    void unknown_vendor_suggestion_is_not_an_install_spec() {
        Lockfile.JdkPin poison = Lockfile.JdkPin.suggested("nosuchvendor", "99");
        assertThat(LockPinMatch.knownVendorId("nosuchvendor")).isFalse();
        assertThat(LockPinMatch.knownVendorId("temurin")).isTrue();
        assertThat(LockPinMatch.suggestionIsInstallable(poison)).isFalse();
        assertThat(LockPinMatch.suggestionIsInstallable(Lockfile.JdkPin.suggested("temurin", "26.0.1")))
                .isTrue();
        assertThat(LockPinMatch.suggestionIsInstallable(Lockfile.JdkPin.suggested("", "25")))
                .isTrue();
        assertThat(LockPinMatch.suggestionIsInstallable(new Lockfile.JdkPin("", "", "temurin", "25.0.4")))
                .isFalse();
    }

    @Test
    void dropped_manifest_pin_does_not_copy_an_unknown_vendor_suggestion() {
        JdkHit temurin = hit("25.0.4", JdkVendor.TEMURIN);
        Lockfile.JdkPin poison = Lockfile.JdkPin.suggested("nosuchvendor", "99");
        assertThat(LockPinMatch.jdkPin(ToolchainSpec.NONE, temurin, poison))
                .isEqualTo(Lockfile.JdkPin.suggested("temurin", "25.0.4"));
        Lockfile.JdkPin colleague = Lockfile.JdkPin.suggested("corretto", "25.0.1");
        assertThat(LockPinMatch.jdkPin(ToolchainSpec.NONE, temurin, colleague)).isEqualTo(colleague);
    }

    @Test
    void graal_choose_ignores_non_graal_hits() {
        JdkHit temurin = hit("25.0.4", JdkVendor.TEMURIN);
        JdkHit ce = hit("25.0.4", JdkVendor.GRAALVM_CE);
        assertThat(LockPinMatch.chooseGraal(List.of(temurin, ce), "graalvm-ce", "25.0.4"))
                .contains(ce);
        assertThat(LockPinMatch.chooseGraal(List.of(temurin), "graalvm-ce", "25.0.4"))
                .isEmpty();
    }

    private static JdkHit hit(String version, JdkVendor vendor) {
        return new JdkHit(Path.of("/jdks", vendor.name() + "-" + version), version, vendor, "jk");
    }
}
