// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.jdk.JdkResolution;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pre-flight against the engine's real summaries: a member's {@code =temurin-21} pin and a
 * member's {@code temurin} beside {@code java = 21} both arrive as the resolver spec
 * {@code temurin-21} — the value the engine's own {@code ensure-jdk} resolves with — and once that
 * JDK is on disk the same walk finds it and asks for nothing.
 */
@Tag("integration")
class JdkPreflightEngineTest {

    @BeforeEach
    @AfterEach
    void forgetRegistries() {
        JdkEnsure.resetSharedRegistries();
    }

    @Test
    void member_pins_pre_flight_as_the_spec_the_engine_resolves_with(@TempDir Path tmp) throws Exception {
        Path root = JdkPreflightTest.workspace(tmp, "exact", "vendor");
        Path exact = JdkPreflightTest.member(root, "exact", "jdk = \"=temurin-21\"\njava = 21\n");
        Path vendor = JdkPreflightTest.member(root, "vendor", "jdk = \"temurin\"\njava = 21\n");
        Path jdks = JdkPreflightTest.jdksWith(tmp.resolve("jdks"), "temurin-25.0.2");
        ProjectInfo rootInfo = Objects.requireNonNull(ProjectInfos.orNull(root), "engine summary of the root");

        List<JdkPreflight.Need> needs = JdkPreflight.needs(root, rootInfo, jdks, ProjectInfos::orNull);

        assertThat(needs).extracting(JdkPreflight.Need::dir).containsExactly(exact, vendor);
        assertThat(needs)
                .extracting(JdkPreflight.Need::pending)
                .containsOnly(new JdkEnsure.Pending("temurin-21", JdkResolution.Tier.PROJECT_TOML));
        assertThat(needs).extracting(JdkPreflight.Need::javaRelease).containsOnly(21);
        ProjectInfo exactInfo = Objects.requireNonNull(ProjectInfos.orNull(exact), "engine summary of the member");
        assertThat(needs.getFirst().jdkSpec()).isEqualTo(exactInfo.jdk()).isEqualTo("temurin-21");

        // With that JDK installed the very same walk resolves it: one download, then nothing pending.
        JdkPreflightTest.jdksWith(jdks, "temurin-21.0.5");
        JdkEnsure.resetSharedRegistries();
        assertThat(JdkPreflight.needs(root, rootInfo, jdks, ProjectInfos::orNull))
                .isEmpty();
    }
}
