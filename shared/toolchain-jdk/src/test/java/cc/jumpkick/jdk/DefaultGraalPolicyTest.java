// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultGraalPolicyTest {

    @Test
    void empty_when_nothing_installed() {
        assertThat(DefaultGraalPolicy.choose(List.of())).isEmpty();
    }

    @Test
    void empty_when_only_non_graal_jdks() {
        assertThat(DefaultGraalPolicy.choose(List.of(hit("25.0.4", JdkVendor.TEMURIN))))
                .isEmpty();
    }

    @Test
    void single_graal_is_the_default() {
        JdkHit only = hit("25.0.4", JdkVendor.GRAALVM_CE);
        assertThat(DefaultGraalPolicy.choose(List.of(only, hit("25.0.4", JdkVendor.TEMURIN))))
                .contains(only);
    }

    @Test
    void prefers_oracle_graalvm_over_ce() {
        JdkHit ce = hit("25.0.4", JdkVendor.GRAALVM_CE);
        JdkHit oracle = hit("25.0.3", JdkVendor.ORACLE_GRAALVM);
        assertThat(DefaultGraalPolicy.choose(List.of(ce, oracle))).contains(oracle);
    }

    @Test
    void newer_version_wins_within_the_same_flavour() {
        JdkHit older = hit("25.0.3", JdkVendor.GRAALVM_CE);
        JdkHit newer = hit("25.0.4", JdkVendor.GRAALVM_CE);
        assertThat(DefaultGraalPolicy.choose(List.of(older, newer))).contains(newer);
    }

    private static JdkHit hit(String version, JdkVendor vendor) {
        return new JdkHit(Path.of("/jdks/" + vendor.name().toLowerCase() + "-" + version), version, vendor, "jk");
    }
}
