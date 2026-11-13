// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClassTagsTest {

    @Test
    void tags_ahead_of_the_type_declaration_are_the_classs() {
        ClassTags tags = ClassTags.ofSource("""
                package com.acme;

                import org.junit.jupiter.api.Tag;

                /** A test class for carts, tagged as a class for the integration tier. */
                @Tags({@Tag("integration"), @Tag("network")})
                final class CartTest {
                    @Test
                    @Tag("slow")
                    void checkout() {}
                }
                """);

        assertThat(tags.classLevel()).containsExactlyInAnyOrder("integration", "network");
        assertThat(tags.all()).containsExactlyInAnyOrder("integration", "network", "slow");
    }

    @Test
    void a_kotlin_class_and_an_untagged_source_read_as_written() {
        assertThat(ClassTags.ofSource("@Tag(\"slow\")\nclass SlowSpec {\n  @Test fun runs() {}\n}\n")
                        .classLevel())
                .containsExactly("slow");
        assertThat(ClassTags.ofSource("class PlainTest {}").all()).isEmpty();
    }
}
