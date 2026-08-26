// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.Os;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * CoreFoundation cannot be faked, so nothing here can stub out a preferences domain. What is
 * testable is the contract that actually matters at a call site: the OS gate, the never-throw
 * discipline, and the shape of any value that does come back. Whether iTerm2 is installed is a
 * property of the machine running the suite — asserted only behind a guard, for the same reason
 * {@link NerdFontDetectTest} injects its font probe instead of trusting {@code fc-list}.
 */
class MacPrefsTest {

    private static boolean onMac() {
        return Os.isDarwin();
    }

    @Test
    void returns_empty_on_non_mac_without_touching_native_code() {
        // Spoofing os.name is the only way to exercise the non-macOS branch here; MacPrefs reads
        // the property live for exactly that reason. Compared before/after rather than asserted
        // false outright, so the test does not depend on running before any other test in the class.
        String previousOsName = System.getProperty("os.name");
        System.setProperty("os.name", "Linux");
        try {
            boolean initBefore = MacPrefs.nativeInitAttempted();
            assertThat(MacPrefs.itermFontName()).isEmpty();
            assertThat(MacPrefs.nativeInitAttempted()).isEqualTo(initBefore);
        } finally {
            if (previousOsName != null) System.setProperty("os.name", previousOsName);
            else System.clearProperty("os.name");
        }
    }

    @Test
    void never_throws_on_this_platform() {
        assertThatCode(MacPrefs::itermFontName).doesNotThrowAnyException();
    }

    @Test
    void never_throws_when_the_env_lookup_itself_blows_up() {
        assertThatCode(() -> MacPrefs.itermFontName(k -> {
                    throw new IllegalStateException("hostile env");
                }))
                .doesNotThrowAnyException();
    }

    @Test
    void an_unknown_profile_name_yields_empty_rather_than_throwing() {
        // Also the stand-in for "iTerm2 preferences absent": no profile can match, which is the
        // same degradation path as a domain that was never written.
        var env = Map.of("ITERM_PROFILE", "no-such-profile-" + UUID.randomUUID());
        assertThat(MacPrefs.itermFontName(env::get)).isEmpty();
    }

    @Test
    void a_blank_profile_name_falls_back_to_the_default_profile() {
        // A blank ITERM_PROFILE must not be matched against Name (no profile is named ""); the
        // default-guid path is used instead, so this agrees with the no-env reading.
        assertThat(MacPrefs.itermFontName(Map.of("ITERM_PROFILE", "   ")::get))
                .isEqualTo(MacPrefs.itermFontName(k -> null));
    }

    @Test
    void repeated_reads_are_stable() {
        // Over-releasing a borrowed CFTypeRef corrupts the runtime rather than throwing, so the
        // symptom would be a crash or a garbage answer on a later call, not a failed assertion on
        // the first one.
        Optional<String> first = MacPrefs.itermFontName();
        for (int i = 0; i < 20; i++) {
            assertThat(MacPrefs.itermFontName()).isEqualTo(first);
        }
    }

    @Test
    void a_reported_font_name_is_trimmed_and_non_blank() {
        assumeTrue(onMac(), "macOS-only preferences domain");
        Optional<String> font = MacPrefs.itermFontName();
        // Presence is a property of this machine, not of the code: asserting it unconditionally
        // would pass on a workstation with iTerm2 and fail on CI, which is backwards.
        assumeTrue(font.isPresent(), "no iTerm2 preferences on this machine");
        assertThat(font.get()).isNotBlank().isEqualTo(font.get().trim());
        assertThat(font.get()).doesNotContain("\n").doesNotContain("\r");
    }
}
