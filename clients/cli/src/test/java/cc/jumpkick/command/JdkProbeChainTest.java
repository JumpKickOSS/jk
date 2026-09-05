// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.discovery.LocalToolProbe;
import cc.jumpkick.discovery.Probes;
import org.junit.jupiter.api.Test;

/**
 * The tier's own probe chain. Every jdk verb that builds a registry without {@code --jdks-dir}
 * consults {@link Probes#defaultChain()}, so this is what decides whether a suite can see, list,
 * default to, write a pointer at, or uninstall a JDK the developer installed with sdkman, mise or
 * the OS. Both test conventions set {@code JK_JDK_PROBES=java-home,jk}; this pins that they do.
 */
class JdkProbeChainTest {

    @Test
    void the_tier_sees_only_the_build_jdk_and_jk_owned_installs() {
        assertThat(Probes.defaultChain())
                .extracting(LocalToolProbe::name)
                .as(
                        "set %s=java-home,jk in the test environment; a suite that can reach the machine's"
                                + " version managers acts on the developer's own JDKs",
                        Probes.ALLOWLIST_ENV)
                .containsExactly("java-home", "jk");
    }
}
