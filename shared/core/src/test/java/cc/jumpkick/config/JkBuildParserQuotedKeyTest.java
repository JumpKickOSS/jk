// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import org.junit.jupiter.api.Test;

/**
 * A quoted TOML key is one name, whatever it contains: a {@code +}, a space, a dot or a letter
 * outside ASCII in a feature, profile, manifest or dependency key reads back verbatim, never as a
 * dotted path.
 */
class JkBuildParserQuotedKeyTest {

    @Test
    void feature_and_profile_names_with_plus_space_and_dot_read_back_verbatim() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                micrometer-java21 = { group = "io.micrometer", version = "1.14.0", optional = true }

                [features."Java 21+"]
                deps = ["micrometer-java21"]

                [profiles."jdk.21+"]
                javac = ["--enable-preview"]
                """);
        assertThat(requireNonNull(parsed.features().byName().get("Java 21+")).deps())
                .containsExactly("micrometer-java21");
        assertThat(requireNonNull(parsed.profiles().byName().get("jdk.21+")).javacArgs())
                .containsExactly("--enable-preview");
    }

    @Test
    void dependency_handles_and_manifest_keys_outside_the_bare_alphabet_read_back_verbatim() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [manifest]
                "Bundle-Name+Version" = "widget 1"
                "Café" = "oui"

                [dependencies]
                "jakarta.json" = { group = "jakarta.json", version = "2.1.3" }
                "netty+epoll" = { group = "io.netty", name = "netty-transport-native-epoll", version = "4.2.0.Final" }
                """);
        assertThat(parsed.manifest())
                .containsEntry("Bundle-Name+Version", "widget 1")
                .containsEntry("Café", "oui");
        assertThat(parsed.dependencies().of(Scope.MAIN))
                .extracting(d -> d.library() + " -> " + d.module())
                .containsExactlyInAnyOrder(
                        "jakarta.json -> jakarta.json:jakarta.json",
                        "netty+epoll -> io.netty:netty-transport-native-epoll");
    }
}
