// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.discovery.DiscoveredTool;
import cc.jumpkick.discovery.LocalToolProbe;
import cc.jumpkick.discovery.ProbeSupport;
import cc.jumpkick.discovery.ToolSpec;
import cc.jumpkick.testing.FakeJdk;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkUninstallPolicyTest {

    @Test
    void owners_and_pointers_are_refused_and_managers_are_not() {
        assertThat(JdkUninstallPolicy.refusedSources()).contains("system", "intellij", "maven-toolchains");
        for (String removable : List.of("jk", "jdks", "sdkman", "mise", "path")) {
            assertThat(JdkUninstallPolicy.removable(removable)).as(removable).isTrue();
            assertThat(JdkUninstallPolicy.refusal(removable)).isEmpty();
        }
    }

    @Test
    void a_version_only_a_pointer_knows_resolves_to_a_refusal_naming_source_and_home(@TempDir Path tmp)
            throws IOException {
        Path home = FakeJdk.create(tmp.resolve("elsewhere/temurin-21"), "21.0.5");
        JdkRegistry registry = new JdkRegistry(
                tmp.resolve("jdks"), List.of(fixed("maven-toolchains", home)), IntellijJdkTable.ofManaged(Set.of()));

        JdkHit hit = registry.findHitBySpec("21").orElseThrow();

        assertThat(hit.source()).isEqualTo("maven-toolchains");
        assertThat(JdkUninstallPolicy.refusal(hit, tmp.resolve("jdks")))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("maven-toolchains")
                        .contains(hit.home().toString()));
        assertThat(home).isDirectory();
    }

    /** A probe that reports {@code homes} under {@code source}. */
    static LocalToolProbe fixed(String source, Path... homes) {
        return new LocalToolProbe() {
            @Override
            public String name() {
                return source;
            }

            @Override
            public Optional<DiscoveredTool> find(ToolSpec spec) {
                return Optional.empty();
            }

            @Override
            public List<JdkHit> discoverAllJdks() {
                return Arrays.stream(homes)
                        .flatMap(h -> ProbeSupport.discoverJdk(h, source).stream())
                        .toList();
            }
        };
    }
}
