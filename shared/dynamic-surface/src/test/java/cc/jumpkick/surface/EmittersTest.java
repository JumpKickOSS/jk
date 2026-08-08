// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import static cc.jumpkick.surface.DynamicSurface.Kind.GENERIC_REFLECTION;
import static cc.jumpkick.surface.DynamicSurface.Kind.JNI_TYPE;
import static cc.jumpkick.surface.DynamicSurface.Kind.PROXY_INTERFACE;
import static cc.jumpkick.surface.DynamicSurface.Kind.REFLECTIVE_MEMBER;
import static cc.jumpkick.surface.DynamicSurface.Kind.REFLECTIVE_TYPE;
import static cc.jumpkick.surface.DynamicSurface.Kind.RESOURCE;
import static cc.jumpkick.surface.DynamicSurface.Kind.SERIALIZATION_TYPE;
import static cc.jumpkick.surface.DynamicSurface.Kind.SERVICE_IMPLEMENTATION;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.surface.DynamicSurface.Entry;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** One model, two formats — including the kinds where the two disagree. */
class EmittersTest {

    @Test
    void a_generic_reflection_type_is_a_class_keep_for_r8_and_nothing_for_native_image() {
        DynamicSurface surface = DynamicSurface.of(
                Entry.type(GENERIC_REFLECTION, "io.micronaut.context.event.ApplicationEventPublisher", "user"));

        assertThat(KeepRuleEmitter.emit(surface))
                .contains("-keep class io.micronaut.context.event.ApplicationEventPublisher { *; }");
        assertThat(ReachabilityMetadataEmitter.emit(surface))
                .doesNotContain("ApplicationEventPublisher")
                .isEqualTo("{\n}\n");
    }

    @Test
    void a_resource_is_a_native_image_include_and_no_keep_rule() {
        DynamicSurface surface = DynamicSurface.of(Entry.type(RESOURCE, "config/app.yml", "train:default"));

        assertThat(KeepRuleEmitter.emit(surface)).doesNotContain("-keep");
        assertThat(ReachabilityMetadataEmitter.emit(surface))
                .contains("\"resources\"")
                .contains("config/app.yml");
    }

    @Test
    void reflective_members_narrow_the_keep_rule() {
        DynamicSurface surface =
                DynamicSurface.of(new Entry(REFLECTIVE_MEMBER, "com.acme.A", Set.of("run", "stop"), "train:default"));

        assertThat(KeepRuleEmitter.emit(surface))
                .contains("-keep class com.acme.A { *** run; *** run(...); *** stop; *** stop(...); }");
        assertThat(ReachabilityMetadataEmitter.emit(surface))
                .contains("\"methods\"")
                .contains("\"run\"")
                .contains("\"stop\"");
    }

    @Test
    void an_initializer_member_has_no_return_type() {
        // Graal's reflect-config lists constructors under `methods` as <init>; `*** <init>;` is
        // not a legal member spec and R8 rejects the whole rule file.
        DynamicSurface surface = DynamicSurface.of(
                new Entry(REFLECTIVE_MEMBER, "com.acme.A", Set.of("<init>", "<clinit>", "run"), "library"));

        assertThat(KeepRuleEmitter.emit(surface))
                .contains("<init>(...);")
                .contains("<clinit>(...);")
                .doesNotContain("*** <init>")
                .doesNotContain("*** <clinit>")
                .contains("*** run;");
    }

    @Test
    void a_member_entry_with_no_members_keeps_the_whole_type() {
        DynamicSurface surface = DynamicSurface.of(Entry.type(REFLECTIVE_MEMBER, "com.acme.A", "x"));

        assertThat(KeepRuleEmitter.emit(surface)).contains("-keep class com.acme.A { *; }");
    }

    @Test
    void each_remaining_kind_emits_its_own_shape() {
        DynamicSurface surface = DynamicSurface.of(
                Entry.type(REFLECTIVE_TYPE, "com.acme.R", "x"),
                Entry.type(SERVICE_IMPLEMENTATION, "com.acme.S", "index"),
                Entry.type(JNI_TYPE, "com.acme.N", "x"),
                Entry.type(PROXY_INTERFACE, "com.acme.I", "x"),
                Entry.type(SERIALIZATION_TYPE, "com.acme.Z", "x"));

        String keeps = KeepRuleEmitter.emit(surface);
        assertThat(keeps)
                .contains("-keep class com.acme.R { *; }")
                .contains("-keep class com.acme.S { *; }")
                .contains("-keep class com.acme.N { *; }")
                .contains("-keep interface com.acme.I { *; }")
                .contains("-keepclassmembers class com.acme.Z");

        String json = ReachabilityMetadataEmitter.emit(surface);
        assertThat(json).contains("\"reflection\"").contains("\"jni\"").contains("\"serialization\"");
    }

    @Test
    void the_origin_rides_the_rule_as_a_comment() {
        DynamicSurface surface = DynamicSurface.of(Entry.type(SERVICE_IMPLEMENTATION, "com.acme.S", "index"));

        assertThat(KeepRuleEmitter.emit(surface)).isEqualTo("# index\n-keep class com.acme.S { *; }\n");
    }

    @Test
    void a_run_of_entries_from_one_origin_gets_one_comment() {
        DynamicSurface surface = DynamicSurface.of(
                Entry.type(SERVICE_IMPLEMENTATION, "com.acme.A", "index"),
                Entry.type(SERVICE_IMPLEMENTATION, "com.acme.B", "index"),
                Entry.type(SERVICE_IMPLEMENTATION, "com.acme.C", "user"));

        assertThat(KeepRuleEmitter.emit(surface)).isEqualTo("""
                        # index
                        -keep class com.acme.A { *; }
                        -keep class com.acme.B { *; }
                        # user
                        -keep class com.acme.C { *; }
                        """);
    }

    @Test
    void an_empty_surface_emits_valid_output_in_both_formats() {
        assertThat(KeepRuleEmitter.emit(DynamicSurface.empty())).isEmpty();
        assertThat(ReachabilityMetadataEmitter.emit(DynamicSurface.empty())).isEqualTo("{\n}\n");
    }

    @Test
    void names_needing_json_escapes_survive() {
        DynamicSurface surface = DynamicSurface.of(Entry.type(RESOURCE, "config/\"odd\"\\path.yml", "x"));

        assertThat(ReachabilityMetadataEmitter.emit(surface)).contains("config/\\\"odd\\\"\\\\path.yml");
    }
}
