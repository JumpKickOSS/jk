// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The classification table, asserted against fixed strings rather than the running host — a
 * predicate that is only ever exercised on the CI runner's OS is a predicate with one arm tested.
 */
class OsTest {

    /** Every {@code os.name} jk has seen, and what each one is. Ordered windows / darwin / linux. */
    private static final String[][] TABLE = {
        // os.name          windows  darwin  linux
        {"Windows 11", "true", "false", "false"},
        {"Windows 10", "true", "false", "false"},
        {"Windows Server 2022", "true", "false", "false"},
        {"Mac OS X", "false", "true", "false"},
        {"macOS", "false", "true", "false"},
        {"Darwin", "false", "true", "false"},
        {"Linux", "false", "false", "true"},
        {"FreeBSD", "false", "false", "false"},
        {"AIX", "false", "false", "false"},
        {"SunOS", "false", "false", "false"},
        {"", "false", "false", "false"},
    };

    @Test
    void classifies_every_os_name_jk_has_seen() {
        for (String[] row : TABLE) {
            String os = row[0];
            assertThat(Os.isWindows(os)).as("isWindows(%s)", os).isEqualTo(Boolean.parseBoolean(row[1]));
            assertThat(Os.isDarwin(os)).as("isDarwin(%s)", os).isEqualTo(Boolean.parseBoolean(row[2]));
            assertThat(Os.isLinux(os)).as("isLinux(%s)", os).isEqualTo(Boolean.parseBoolean(row[3]));
        }
    }

    /**
     * The defect the copies shipped: {@code "win"} is a substring of {@code Darwin}, so the short
     * test calls a Mac a Windows box. Asserted on the predicate, not on {@code String.contains},
     * so shortening the test back fails here rather than passing a tautology.
     */
    @Test
    void windows_predicate_is_not_satisfied_by_darwin() {
        assertThat("Darwin".toLowerCase().contains("win")).isTrue();
        assertThat(Os.isWindows("Darwin")).isFalse();
        assertThat(Os.isWindows("darwin")).isFalse();
    }

    /** Case folds, so a vendor that ships {@code WINDOWS_NT} or {@code linux} is still classified. */
    @Test
    void classification_is_case_insensitive() {
        assertThat(Os.isWindows("WINDOWS_NT")).isTrue();
        assertThat(Os.isLinux("LINUX")).isTrue();
        assertThat(Os.isDarwin("MAC OS X")).isTrue();
    }

    /** A null name is unclassifiable, not an NPE — callers pass values straight out of a probe. */
    @Test
    void a_null_name_matches_nothing() {
        assertThat(Os.isWindows(null)).isFalse();
        assertThat(Os.isDarwin(null)).isFalse();
        assertThat(Os.isLinux(null)).isFalse();
    }

    /** The no-arg predicates read {@link Os#name()}, so they agree with the String forms. */
    @Test
    void live_predicates_agree_with_the_string_forms() {
        String os = Os.name();
        assertThat(os).isNotNull();
        assertThat(Os.isWindows()).isEqualTo(Os.isWindows(os));
        assertThat(Os.isDarwin()).isEqualTo(Os.isDarwin(os));
        assertThat(Os.isLinux()).isEqualTo(Os.isLinux(os));
        // The running host is exactly one of the three jk supports.
        assertThat(List.of(Os.isWindows(), Os.isDarwin(), Os.isLinux())).containsOnlyOnce(true);
    }
}
