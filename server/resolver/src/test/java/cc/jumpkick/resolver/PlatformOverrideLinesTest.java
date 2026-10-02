// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.resolver.PlatformConstraints.ManagementOverride;
import cc.jumpkick.resolver.PlatformConstraints.OtherSay;
import cc.jumpkick.resolver.PlatformConstraints.OverrideLines;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The folded BOM-override line names a few modules in full and counts the rest. */
class PlatformOverrideLinesTest {

    private static ManagementOverride meeting(String module, String asked) {
        return new ManagementOverride(
                module, "2.0", "org.example:bom-a:1.0", false, List.of(new OtherSay("org.example:bom-b:1.0", asked)));
    }

    @Test
    void a_pair_meeting_on_many_modules_lists_three_by_name_and_counts_the_rest() {
        OverrideLines lines = new OverrideLines();
        for (String m : List.of("e", "b", "d", "a", "c")) lines.add(meeting("com.foo:" + m, "1.0"));

        assertThat(lines.render())
                .containsExactly("org.example:bom-a:1.0 wins over org.example:bom-b:1.0 on 5 modules it manages higher:"
                        + " com.foo:a 2.0 over 1.0, com.foo:b 2.0 over 1.0, com.foo:c 2.0 over 1.0 and 2 more"
                        + " — the highest version wins");
    }

    @Test
    void a_pair_meeting_on_three_modules_lists_them_all() {
        OverrideLines lines = new OverrideLines();
        for (String m : List.of("a", "b", "c")) lines.add(meeting("com.foo:" + m, "1.0"));

        assertThat(lines.render().getFirst())
                .contains("on 3 modules it manages higher: com.foo:a 2.0 over 1.0, com.foo:b 2.0 over 1.0,"
                        + " com.foo:c 2.0 over 1.0 — ");
    }
}
