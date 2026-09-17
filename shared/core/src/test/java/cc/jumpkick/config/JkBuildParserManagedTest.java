// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import org.junit.jupiter.api.Test;

/**
 * {@code [managed-dependencies]} reads with the dependency grammar and lands in its own scope; an
 * entry without a version has nothing to pin and is refused.
 */
class JkBuildParserManagedTest {

    @Test
    void managed_entries_read_in_every_dependency_spelling() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                guava = "com.google.guava:guava:33.4.0-jre"

                [managed-dependencies]
                commons-io = "commons-io:commons-io:2.16.1"
                snakeyaml  = { group = "org.yaml", name = "snakeyaml", version = "2.3" }
                """);

        assertThat(parsed.dependencies().of(Scope.MANAGED))
                .extracting(Dependency::module, d -> d.version().raw())
                .containsExactly(tuple("commons-io:commons-io", "2.16.1"), tuple("org.yaml:snakeyaml", "2.3"));
        assertThat(parsed.dependencies().of(Scope.MANAGED))
                .allSatisfy(d -> assertThat(d.version()).isInstanceOf(VersionSelector.Exact.class));
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(1);
    }

    @Test
    void a_managed_entry_without_a_version_is_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [managed-dependencies]
                        commons-io = "commons-io:commons-io"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("managed-dependencies.commons-io must name a version");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [managed-dependencies]
                        core = { path = "../core" }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("managed-dependencies.core must name a version");
    }
}
