// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The allowlist keeps named probes in chain order and never widens on a miss. */
class ProbesTest {

    private static List<String> names(List<LocalToolProbe> chain) {
        return chain.stream().map(LocalToolProbe::name).toList();
    }

    @Test
    void the_full_chain_starts_with_the_env_hint_and_ends_with_the_os() {
        List<String> all = names(Probes.fullChain());
        assertThat(all).startsWith("java-home", "jk").contains("sdkman", "intellij", "gradle");
        assertThat(all.indexOf("system")).isGreaterThan(all.indexOf("sdkman"));
    }

    @Test
    void an_allowlist_keeps_the_named_probes_in_chain_order_whatever_order_it_names_them() {
        List<LocalToolProbe> chain = Probes.fullChain();
        assertThat(names(Probes.restrict(chain, " jk , java-home "))).containsExactly("java-home", "jk");
        assertThat(names(Probes.restrict(chain, "system,sdkman"))).containsExactly("sdkman", "system");
    }

    @Test
    void a_blank_or_absent_allowlist_is_the_whole_chain() {
        List<LocalToolProbe> chain = Probes.fullChain();
        assertThat(Probes.restrict(chain, null)).isSameAs(chain);
        assertThat(Probes.restrict(chain, "  ")).isSameAs(chain);
    }

    @Test
    void an_allowlist_naming_no_probe_is_empty_not_everything() {
        assertThat(Probes.restrict(Probes.fullChain(), "sdkmann, ,")).isEmpty();
    }
}
