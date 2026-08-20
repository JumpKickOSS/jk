// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class Giter8ShortNamesTest {

    @Test
    void catalog_has_language_and_layout_for_each_entry() {
        assertThat(Giter8ShortNames.entries()).isNotEmpty();
        for (var e : Giter8ShortNames.entries()) {
            assertThat(e.languages()).isNotEmpty();
            assertThat(e.layout())
                    .isIn(
                            Giter8ShortNames.LAYOUT_SIMPLE,
                            Giter8ShortNames.LAYOUT_TRADITIONAL,
                            Giter8ShortNames.LAYOUT_CUSTOM);
            assertThat(e.supports(e.languages().get(0))).isTrue();
        }
    }

    @Test
    void kotlin_templates_not_shown_for_java() {
        assertThat(Giter8ShortNames.find("ktor-3")).get().satisfies(e -> {
            assertThat(e.supports("kotlin")).isTrue();
            assertThat(e.supports("java")).isFalse();
            assertThat(e.supports("groovy")).isFalse();
        });
        assertThat(Giter8ShortNames.find("grails-8")).get().satisfies(e -> {
            assertThat(e.supports("groovy")).isTrue();
            assertThat(e.supports("java")).isFalse();
        });
        assertThat(Giter8ShortNames.find("java-cli")).get().satisfies(e -> assertThat(e.supports("java"))
                .isTrue());
    }

    @Test
    void normalize_layout_defaults_to_traditional() {
        assertThat(Giter8ShortNames.normalizeLayout(null)).isEqualTo(Giter8ShortNames.LAYOUT_TRADITIONAL);
        assertThat(Giter8ShortNames.normalizeLayout("")).isEqualTo(Giter8ShortNames.LAYOUT_TRADITIONAL);
        assertThat(Giter8ShortNames.normalizeLayout("nope")).isEqualTo(Giter8ShortNames.LAYOUT_TRADITIONAL);
        assertThat(Giter8ShortNames.normalizeLayout("simple")).isEqualTo(Giter8ShortNames.LAYOUT_SIMPLE);
    }

    @Test
    void languages_from_properties_accepts_jk_languages() {
        assertThat(Giter8ShortNames.languagesFromProperties(Map.of("jk_languages", "kotlin")))
                .containsExactly("kotlin");
        assertThat(Giter8ShortNames.languagesFromProperties(Map.of("jk_languages", "java, kotlin")))
                .containsExactly("java", "kotlin");
        assertThat(Giter8ShortNames.languagesFromProperties(Map.of("language", "groovy")))
                .containsExactly("groovy");
        // JDK pin mis-keyed as language= — ignored
        assertThat(Giter8ShortNames.languagesFromProperties(Map.of("language", "25")))
                .isEmpty();
        assertThat(Giter8ShortNames.languagesFromProperties(Map.of())).isEmpty();
    }
}
