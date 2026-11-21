// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.run.SourceLocation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A member's manifest refusal names the place once: its file when it has one, else the member. */
class GroupRunRefusalTest {

    @Test
    void a_refusal_located_in_a_file_leads_with_the_file_alone(@TempDir Path dir) {
        Path manifest = dir.resolve("libs/jk.toml").toAbsolutePath().normalize();
        JkBuildParseException e =
                new JkBuildParseException("[build] unknown key `bogus`", SourceLocation.at(7, 1)).locatedIn(manifest);

        assertThat(GroupRun.refusal("libs", e)).isEqualTo(manifest + ":7:1: [build] unknown key `bogus`");
    }

    @Test
    void a_refusal_of_a_whole_file_names_the_file_alone(@TempDir Path dir) {
        Path manifest = dir.resolve("libs/jk.toml").toAbsolutePath().normalize();
        JkBuildParseException e =
                new JkBuildParseException("jk.toml is missing required key `name`").locatedIn(manifest);

        assertThat(GroupRun.refusal("libs", e)).isEqualTo(manifest + ": jk.toml is missing required key `name`");
    }

    @Test
    void a_refusal_no_file_owns_is_named_by_its_member() {
        assertThat(GroupRun.refusal("libs", new JkBuildParseException("workspace modules missing jk.toml: [a]")))
                .isEqualTo("libs: workspace modules missing jk.toml: [a]");
    }
}
