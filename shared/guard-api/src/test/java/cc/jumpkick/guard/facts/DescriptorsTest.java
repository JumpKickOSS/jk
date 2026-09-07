// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DescriptorsTest {

    @Test
    void binary_and_internal_names_are_each_others_inverse() {
        assertThat(Descriptors.binaryName("cc/jumpkick/host/Hashing")).isEqualTo("cc.jumpkick.host.Hashing");
        assertThat(Descriptors.internalName("cc.jumpkick.host.Hashing")).isEqualTo("cc/jumpkick/host/Hashing");
        assertThat(Descriptors.internalName(Descriptors.binaryName("a/b/C$D"))).isEqualTo("a/b/C$D");
    }

    @Test
    void a_default_package_class_has_no_slash_and_an_empty_package() {
        assertThat(Descriptors.binaryName("Main")).isEqualTo("Main");
        assertThat(Descriptors.packageOf("Main")).isEmpty();
        assertThat(Descriptors.packageOf("a/b/C")).isEqualTo("a.b");
        assertThat(Descriptors.packageOf("a/C")).isEqualTo("a");
    }

    @Test
    void an_object_descriptor_becomes_its_binary_type_name() {
        assertThat(Descriptors.typeName("Lorg/junit/jupiter/api/Tag;")).isEqualTo("org.junit.jupiter.api.Tag");
        assertThat(Descriptors.typeName("La/b/C$Inner;")).isEqualTo("a.b.C$Inner");
        assertThat(Descriptors.typeName("La;")).isEqualTo("a");
    }

    @Test
    void primitive_and_array_descriptors_are_returned_as_written() {
        assertThat(Descriptors.typeName("I")).isEqualTo("I");
        assertThat(Descriptors.typeName("Z")).isEqualTo("Z");
        assertThat(Descriptors.typeName("[Ljava/lang/String;")).isEqualTo("[Ljava/lang/String;");
        assertThat(Descriptors.typeName("[[J")).isEqualTo("[[J");
        assertThat(Descriptors.typeName("L;"))
                .as("too short to be a class descriptor")
                .isEqualTo("L;");
    }

    @Test
    void the_outermost_class_drops_every_nested_and_anonymous_suffix() {
        assertThat(Descriptors.outermost("a/b/C$D$1")).isEqualTo("a/b/C");
        assertThat(Descriptors.outermost("a/b/C$1")).isEqualTo("a/b/C");
        assertThat(Descriptors.outermost("a/b/C")).isEqualTo("a/b/C");
        assertThat(Descriptors.outermost("C$Inner")).isEqualTo("C");
    }
}
