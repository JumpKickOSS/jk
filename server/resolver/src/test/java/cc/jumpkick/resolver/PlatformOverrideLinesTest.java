// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.resolver.PlatformConstraints.LaterSay;
import cc.jumpkick.resolver.PlatformConstraints.ManagementOverride;
import cc.jumpkick.resolver.PlatformConstraints.OverrideLines;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The folded BOM-override line names a few modules in full and counts the rest. */
class PlatformOverrideLinesTest {

    private static ManagementOverride meeting(String module, String asked) {
        return new ManagementOverride(
                module, "1.0", "org.example:bom-a:1.0", false, List.of(new LaterSay("org.example:bom-b:1.0", asked)));
    }

    @Test
    void a_pair_meeting_on_many_modules_lists_three_by_name_and_counts_the_rest() {
        OverrideLines lines = new OverrideLines();
        for (String m : List.of("e", "b", "d", "a", "c")) lines.add(meeting("com.foo:" + m, "2.0"));

        assertThat(lines.render())
                .containsExactly("org.example:bom-a:1.0 wins over org.example:bom-b:1.0 on 5 modules it manages first:"
                        + " com.foo:a 1.0 over 2.0, com.foo:b 1.0 over 2.0, com.foo:c 1.0 over 2.0 and 2 more"
                        + " — the first-declared BOM wins, as the first import does under Maven");
    }

    @Test
    void a_pair_meeting_on_three_modules_lists_them_all() {
        OverrideLines lines = new OverrideLines();
        for (String m : List.of("a", "b", "c")) lines.add(meeting("com.foo:" + m, "2.0"));

        assertThat(lines.render().getFirst())
                .contains("on 3 modules it manages first: com.foo:a 1.0 over 2.0, com.foo:b 1.0 over 2.0,"
                        + " com.foo:c 1.0 over 2.0 — ");
    }
}
