// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A compiled Kotlin method is named in the graph as its source names it. */
class KotlinNamesTest {

    @Test
    void a_value_class_mangled_name_is_the_declared_name() {
        assertThat(JvmFacts.kotlinName("lookup-4ZD5Yi0")).isEqualTo("lookup");
        assertThat(JvmFacts.kotlinName("getRaw-impl")).isEqualTo("getRaw");
        assertThat(JvmFacts.kotlinName("equals-impl0")).isEqualTo("equals");
    }

    @Test
    void a_value_classs_construction_is_its_constructor() {
        assertThat(JvmFacts.kotlinName("constructor-impl")).isEqualTo("constructor");
        assertThat(JvmFacts.kotlinName("box-impl")).isEqualTo("constructor");
        assertThat(JvmFacts.kotlinName("unbox-impl")).isEqualTo("constructor");
    }

    @Test
    void any_other_name_is_kept() {
        assertThat(JvmFacts.kotlinName("greet")).isEqualTo("greet");
        assertThat(JvmFacts.kotlinName("greet$default")).isEqualTo("greet$default");
        assertThat(JvmFacts.kotlinName("<init>")).isEqualTo("<init>");
    }
}
