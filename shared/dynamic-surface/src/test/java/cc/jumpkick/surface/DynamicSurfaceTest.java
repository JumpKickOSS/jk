// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import static cc.jumpkick.surface.DynamicSurface.Kind.GENERIC_REFLECTION;
import static cc.jumpkick.surface.DynamicSurface.Kind.PROXY_INTERFACE;
import static cc.jumpkick.surface.DynamicSurface.Kind.REFLECTIVE_MEMBER;
import static cc.jumpkick.surface.DynamicSurface.Kind.REFLECTIVE_TYPE;
import static cc.jumpkick.surface.DynamicSurface.Kind.RESOURCE;
import static cc.jumpkick.surface.DynamicSurface.Kind.SERVICE_IMPLEMENTATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.surface.DynamicSurface.Entry;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The model and its merge; the emitters are covered separately. */
class DynamicSurfaceTest {

    @Test
    void merging_unions_members_and_credits_every_origin() {
        DynamicSurface a =
                DynamicSurface.of(new Entry(REFLECTIVE_MEMBER, "com.acme.A", Set.of("run"), "train:default"));
        DynamicSurface b = DynamicSurface.of(new Entry(REFLECTIVE_MEMBER, "com.acme.A", Set.of("stop"), "train:prod"));

        assertThat(a.merge(b).entries()).singleElement().satisfies(e -> {
            assertThat(e.members()).containsExactly("run", "stop");
            assertThat(e.origin()).isEqualTo("train:default,train:prod");
        });
    }

    @Test
    void the_same_name_under_different_kinds_stays_separate() {
        DynamicSurface merged = DynamicSurface.of(Entry.type(REFLECTIVE_TYPE, "com.acme.A", "index"))
                .merge(DynamicSurface.of(Entry.type(GENERIC_REFLECTION, "com.acme.A", "user")));

        assertThat(merged.entries()).hasSize(2);
    }

    @Test
    void merge_order_does_not_change_the_result() {
        DynamicSurface a = DynamicSurface.of(
                Entry.type(REFLECTIVE_TYPE, "com.acme.Z", "index"), Entry.type(REFLECTIVE_TYPE, "com.acme.A", "index"));
        DynamicSurface b = DynamicSurface.of(Entry.type(SERVICE_IMPLEMENTATION, "com.acme.M", "library"));

        assertThat(a.merge(b).entries()).isEqualTo(b.merge(a).entries());
    }

    @Test
    void entries_are_sorted_by_kind_then_name() {
        DynamicSurface merged = DynamicSurface.empty()
                .merge(DynamicSurface.of(
                        Entry.type(SERVICE_IMPLEMENTATION, "com.acme.S", "x"),
                        Entry.type(REFLECTIVE_TYPE, "com.acme.Z", "x"),
                        Entry.type(REFLECTIVE_TYPE, "com.acme.A", "x")));

        assertThat(merged.entries()).extracting(Entry::name).containsExactly("com.acme.A", "com.acme.Z", "com.acme.S");
    }

    @Test
    void merging_nothing_is_a_no_op() {
        DynamicSurface one = DynamicSurface.of(Entry.type(REFLECTIVE_TYPE, "com.acme.A", "index"));

        assertThat(one.merge(DynamicSurface.empty()).entries()).isEqualTo(one.entries());
        assertThat(one.merge((DynamicSurface) null).entries()).isEqualTo(one.entries());
    }

    @Test
    void of_filters_by_kind() {
        DynamicSurface surface = DynamicSurface.of(
                Entry.type(REFLECTIVE_TYPE, "com.acme.A", "x"),
                Entry.type(RESOURCE, "config/app.yml", "x"),
                Entry.type(PROXY_INTERFACE, "com.acme.I", "x"));

        assertThat(surface.of(RESOURCE)).extracting(Entry::name).containsExactly("config/app.yml");
    }

    @Test
    void a_blank_name_is_rejected() {
        assertThatThrownBy(() -> Entry.type(REFLECTIVE_TYPE, "  ", "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
