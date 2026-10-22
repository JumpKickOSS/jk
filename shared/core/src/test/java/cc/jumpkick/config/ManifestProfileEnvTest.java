// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

class ManifestProfileEnvTest {

    @Test
    void a_profile_reads_its_env_table() {
        var profiles = ManifestTables.parseProfiles(Toml.parse("""
                [profiles.integration]
                include-tags = ["integration"]
                env = { JK_HTTP_DENY_HOSTS = "repo.maven.apache.org", RETRIES = 3 }
                """));
        assertThat(profiles.resolve("integration").env())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("JK_HTTP_DENY_HOSTS", "repo.maven.apache.org", "RETRIES", "3"));
    }

    @Test
    void env_must_be_a_table() {
        assertThatThrownBy(() -> ManifestTables.parseProfiles(Toml.parse("[profiles.p]\nenv = \"X=1\"\n")))
                .hasMessageContaining("profiles.p.env must be a table");
    }
}
