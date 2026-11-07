// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

class ManifestProfileAllSuitesTest {

    @Test
    void a_profile_reads_its_all_suites_switch_and_a_child_inherits_it() {
        var profiles = ManifestTables.parseProfiles(Toml.parse("""
                [profiles.integration]
                include-tags = ["integration"]
                all-suites = true

                [profiles.nightly]
                inherits = "integration"

                [profiles.slow]
                include-tags = ["slow"]
                """));
        assertThat(profiles.resolve("integration").allSuites()).isTrue();
        assertThat(profiles.resolve("nightly").allSuites()).isTrue();
        assertThat(profiles.resolve("slow").allSuites()).isFalse();
    }

    @Test
    void all_suites_must_be_a_boolean() {
        assertThatThrownBy(() -> ManifestTables.parseProfiles(Toml.parse("[profiles.p]\nall-suites = \"yes\"\n")))
                .hasMessageContaining("profiles.p.all-suites must be true or false");
    }
}
