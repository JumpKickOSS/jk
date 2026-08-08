// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code jk format}'s giter8 exclusion must match the actual giter8 shape ({@code *.g8}, a bare
 * {@code g8} dir, or a {@code $placeholder$} segment) — not any bare "templates"/"giter8" segment
 * name or any name that merely contains a {@code $}, which excluded this repo's own
 * {@code cc.jumpkick.templates} package from formatting.
 */
class FormatPlansNotExcludedTest {

    @Test
    void a_dot_g8_template_root_is_excluded() {
        assertThat(FormatPlans.notExcluded(Path.of("templates/quarkus.g8/src/main/Foo.java")))
                .isFalse();
    }

    @Test
    void a_bare_g8_dir_is_excluded() {
        assertThat(FormatPlans.notExcluded(Path.of("proj/src/main/g8/Foo.java")))
                .isFalse();
    }

    @Test
    void a_placeholder_segment_is_excluded() {
        assertThat(FormatPlans.notExcluded(Path.of("templates/quarkus.g8/src/main/$package$/App.java")))
                .isFalse();
    }

    @Test
    void build_and_vcs_dirs_are_excluded() {
        assertThat(FormatPlans.notExcluded(Path.of("proj/target/Gen.java"))).isFalse();
        assertThat(FormatPlans.notExcluded(Path.of("proj/build/Gen.java"))).isFalse();
        assertThat(FormatPlans.notExcluded(Path.of("proj/.git/Gen.java"))).isFalse();
        assertThat(FormatPlans.notExcluded(Path.of("proj/node_modules/Gen.java")))
                .isFalse();
    }

    @Test
    void a_bare_templates_package_is_not_excluded() {
        // The bug: cc.jumpkick.templates (OfficialTemplatesFreshen.java, no .g8 in sight) was
        // silently skipped by jk format because "templates" alone used to be an exclusion segment.
        assertThat(FormatPlans.notExcluded(
                        Path.of("shared/core/src/main/java/cc/jumpkick/templates/OfficialTemplatesFreshen.java")))
                .isTrue();
    }

    @Test
    void a_bare_giter8_package_is_not_excluded() {
        assertThat(FormatPlans.notExcluded(Path.of("clients/cli/src/main/java/cc/jumpkick/command/giter8/Foo.java")))
                .isTrue();
    }

    @Test
    void a_dollar_sign_that_is_not_a_placeholder_is_not_excluded() {
        assertThat(FormatPlans.notExcluded(Path.of("proj/src/main/java/demo/a$b/Foo.java")))
                .isTrue();
    }
}
