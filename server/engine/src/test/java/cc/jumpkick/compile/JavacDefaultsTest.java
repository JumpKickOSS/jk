// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.DebugInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavacDefaultsTest {

    @Test
    void full_debug_info_then_lint_then_user_args() {
        List<String> args = JavacDefaults.effectiveArgs(true, DebugInfo.FULL, List.of(), List.of("-Werror"));
        assertThat(args).containsExactly("-g", "-Xlint:deprecation,unchecked", "-Werror");
    }

    @Test
    void omits_lint_when_disabled() {
        assertThat(JavacDefaults.effectiveArgs(false, DebugInfo.FULL, List.of(), List.of("-Werror")))
                .containsExactly("-g", "-Werror");
    }

    @Test
    void each_debug_level_is_one_javac_flag() {
        assertThat(JavacDefaults.effectiveArgs(false, DebugInfo.LINES, List.of(), List.of()))
                .containsExactly("-g:source,lines");
        assertThat(JavacDefaults.effectiveArgs(false, DebugInfo.NONE, List.of(), List.of()))
                .containsExactly("-g:none");
    }

    @Test
    void contributed_args_land_between_the_defaults_and_the_user_s() {
        assertThat(JavacDefaults.effectiveArgs(true, DebugInfo.FULL, List.of("-parameters"), List.of("-Werror")))
                .containsExactly("-g", "-Xlint:deprecation,unchecked", "-parameters", "-Werror");
    }

    @Test
    void a_contributed_arg_the_user_passes_is_not_duplicated() {
        assertThat(JavacDefaults.effectiveArgs(false, DebugInfo.FULL, List.of("-parameters"), List.of("-parameters")))
                .containsExactly("-g", "-parameters");
    }
}
