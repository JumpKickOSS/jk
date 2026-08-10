// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import static cc.jumpkick.surface.DynamicSurface.Kind.JNI_TYPE;
import static cc.jumpkick.surface.DynamicSurface.Kind.PROXY_INTERFACE;
import static cc.jumpkick.surface.DynamicSurface.Kind.REFLECTIVE_MEMBER;
import static cc.jumpkick.surface.DynamicSurface.Kind.REFLECTIVE_TYPE;
import static cc.jumpkick.surface.DynamicSurface.Kind.RESOURCE;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.surface.DynamicSurface.Entry;
import org.junit.jupiter.api.Test;

/** Library-published Graal metadata becoming R8 keep rules, which is the point of the model. */
class NativeImageMetadataTest {

    @Test
    void a_type_asking_for_named_fields_becomes_a_member_entry() {
        // The real shape, taken from micronaut-http-netty's reflect-config.json.
        String body = """
                [
                  {
                    "name":"io.netty.util.internal.shaded.org.jctools.queues.MpmcArrayQueueConsumerIndexField",
                    "fields":[{"name":"consumerIndex"}]
                  }
                ]
                """;

        DynamicSurface surface = NativeImageMetadata.parse(
                "META-INF/native-image/io.micronaut/http-netty/reflect-config.json", body, "library:http-netty");

        assertThat(surface.entries()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(REFLECTIVE_MEMBER);
            assertThat(e.members()).containsExactly("f:consumerIndex");
            assertThat(e.origin()).isEqualTo("library:http-netty");
        });
        assertThat(KeepRuleEmitter.emit(surface))
                .contains("*** consumerIndex;")
                .doesNotContain("consumerIndex(...)");
        // A recorded field access round-trips to a `fields` entry, not a guessed method (JK-1753).
        assertThat(ReachabilityMetadataEmitter.emit(surface))
                .contains("\"fields\":[{\"name\":\"consumerIndex\"}]")
                .doesNotContain("\"methods\"");
    }

    @Test
    void an_all_declared_flag_keeps_the_whole_type() {
        String body = """
                [{"name":"com.acme.A","allDeclaredMethods":true,"methods":[{"name":"run"}]}]
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/reflect-config.json", body, "library:acme");

        assertThat(surface.of(REFLECTIVE_TYPE)).extracting(Entry::name).containsExactly("com.acme.A");
        assertThat(KeepRuleEmitter.emit(surface)).contains("-keep class com.acme.A { *; }");
    }

    @Test
    void the_unified_schema_reads_every_section() {
        // The real unified shapes: proxies are reflection entries with a map-shaped type, and
        // resources are a flat glob array (JK-1752).
        String body = """
                {
                  "reflection": [
                    {"type":"com.acme.R","allDeclaredMethods":true},
                    {"type":{"proxy":["com.acme.I","com.acme.J"]}}
                  ],
                  "jni": [{"type":"com.acme.N"}],
                  "resources": [{"glob":"config/*.yml"}]
                }
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/reachability-metadata.json", body, "library:acme");

        assertThat(surface.of(REFLECTIVE_TYPE)).extracting(Entry::name).containsExactly("com.acme.R");
        assertThat(surface.of(JNI_TYPE)).extracting(Entry::name).containsExactly("com.acme.N");
        // One entry per proxy declaration, carrying the whole ordered list (JK-1799).
        assertThat(surface.of(PROXY_INTERFACE)).extracting(Entry::name).containsExactly("com.acme.I,com.acme.J");
        assertThat(surface.of(RESOURCE)).extracting(Entry::name).containsExactly("config/*.yml");
    }

    @Test
    void a_multi_interface_proxy_round_trips_as_one_ordered_list() {
        // GraalVM matches proxy registrations by the exact ordered interface list; splitting
        // ["I","J"] into two single-interface registrations would never match the runtime
        // Proxy.newProxyInstance lookup (JK-1799). Order is the declaration's, not sorted.
        String body = """
                [{"interfaces":["com.acme.J","com.acme.I"]}]
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/proxy-config.json", body, "lib");

        assertThat(surface.of(PROXY_INTERFACE)).extracting(Entry::name).containsExactly("com.acme.J,com.acme.I");
        assertThat(ReachabilityMetadataEmitter.emit(surface))
                .contains("{\"type\":{\"proxy\":[\"com.acme.J\",\"com.acme.I\"]}}");
        assertThat(KeepRuleEmitter.emit(surface))
                .contains("-keep interface com.acme.J { *; }")
                .contains("-keep interface com.acme.I { *; }");

        DynamicSurface back = NativeImageMetadata.parse(
                "x/reachability-metadata.json", ReachabilityMetadataEmitter.emit(surface), "lib");
        assertThat(back.of(PROXY_INTERFACE)).extracting(Entry::name).containsExactly("com.acme.J,com.acme.I");
    }

    @Test
    void the_legacy_reflection_proxies_section_is_still_read() {
        // Files jk emitted before JK-1752 put proxies in a top-level "reflection-proxies" array
        // and resources under {"resources":{"includes":[...]}} — keep reading both.
        String body = """
                {
                  "reflection-proxies": [{"interfaces":["com.acme.I"]}],
                  "resources": {"includes":[{"glob":"config/*.yml"}]}
                }
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/reachability-metadata.json", body, "old");

        assertThat(surface.of(PROXY_INTERFACE)).extracting(Entry::name).containsExactly("com.acme.I");
        assertThat(surface.of(RESOURCE)).extracting(Entry::name).containsExactly("config/*.yml");
    }

    @Test
    void emitted_metadata_round_trips_through_the_parser() {
        DynamicSurface surface = DynamicSurface.of(
                Entry.type(PROXY_INTERFACE, "com.acme.I", "train:default"),
                Entry.type(RESOURCE, "config/*.yml", "train:default"));

        String json = ReachabilityMetadataEmitter.emit(surface);
        assertThat(json)
                .contains("{\"type\":{\"proxy\":[\"com.acme.I\"]}}")
                .contains("\"resources\": [\n    {\"glob\":\"config/*.yml\"}\n  ]")
                .doesNotContain("reflection-proxies")
                .doesNotContain("includes");

        DynamicSurface back = NativeImageMetadata.parse("x/reachability-metadata.json", json, "train:default");
        assertThat(back.of(PROXY_INTERFACE)).extracting(Entry::name).containsExactly("com.acme.I");
        assertThat(back.of(RESOURCE)).extracting(Entry::name).containsExactly("config/*.yml");
    }

    @Test
    void the_split_resource_schema_translates_literal_patterns_to_globs() {
        // Old-schema patterns are Java regexes; \Qapplication.yml\E re-emitted as a glob would
        // match nothing, so literal regexes translate to the literal they quote (JK-1777).
        String body = """
                {"resources":{"includes":[{"pattern":"\\\\Qapplication.yml\\\\E"}]}}
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/resource-config.json", body, "lib");

        assertThat(surface.of(RESOURCE)).extracting(Entry::name).containsExactly("application.yml");
        assertThat(ReachabilityMetadataEmitter.emit(surface)).contains("{\"glob\":\"application.yml\"}");
    }

    @Test
    void untranslatable_patterns_ride_in_a_split_format_sidecar() {
        // `.properties$` has no exact glob form. It must not be emitted as a glob; it ships in a
        // legacy split-format resource-config.json, which native-image still reads (JK-1777).
        String body = """
                {"resources":{"includes":[{"pattern":".*[.]properties$"}]}}
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/resource-config.json", body, "lib");

        assertThat(surface.of(DynamicSurface.Kind.RESOURCE_PATTERN))
                .extracting(Entry::name)
                .containsExactly(".*[.]properties$");
        assertThat(surface.of(RESOURCE)).isEmpty();
        assertThat(ReachabilityMetadataEmitter.emit(surface)).doesNotContain("properties");
        assertThat(ReachabilityMetadataEmitter.emitResourceConfig(surface))
                .isEqualTo("{\"resources\":{\"includes\":[\n  {\"pattern\":\".*[.]properties$\"}\n]}}\n");
    }

    @Test
    void regex_to_glob_translates_only_the_faithful_forms() {
        assertThat(NativeImageMetadata.regexToGlob("\\Qapplication.yml\\E")).isEqualTo("application.yml");
        assertThat(NativeImageMetadata.regexToGlob("\\Qconfig/\\E.*")).isEqualTo("config/**");
        assertThat(NativeImageMetadata.regexToGlob("config/.*")).isEqualTo("config/**");
        assertThat(NativeImageMetadata.regexToGlob(".*")).isEqualTo("**");
        assertThat(NativeImageMetadata.regexToGlob(".*\\Q/app.yml\\E")).isEqualTo("**/app.yml");
        assertThat(NativeImageMetadata.regexToGlob("application\\.yml")).isEqualTo("application.yml");
        // A `.*` glued to a non-slash neighbour cannot become `**` (GraalVM requires ** to be a
        // whole level) and `*` would stop crossing directories — untranslatable.
        assertThat(NativeImageMetadata.regexToGlob("\\Qmessages\\E.*")).isNull();
        // A bare `.` matches any character; a quoted `*` has no glob escape; classes stay regex.
        assertThat(NativeImageMetadata.regexToGlob("application.yml")).isNull();
        assertThat(NativeImageMetadata.regexToGlob("\\Qweird*name\\E")).isNull();
        assertThat(NativeImageMetadata.regexToGlob("[abc]+")).isNull();
        assertThat(NativeImageMetadata.regexToGlob("\\d+")).isNull();
    }

    @Test
    void the_tracing_agent_writes_resources_as_a_bare_list() {
        // What `-agentlib:native-image-agent` actually produces, as opposed to the nested
        // {"resources": {"includes": [...]}} the split schema uses.
        String body = """
                {"reflection": [], "resources": [{"glob":"META-INF/micronaut"},{"glob":"application.properties"}]}
                """;

        assertThat(NativeImageMetadata.parse("x/reachability-metadata.json", body, "train")
                        .of(RESOURCE))
                .extracting(Entry::name)
                .containsExactly("META-INF/micronaut", "application.properties");
    }

    @Test
    void jni_and_serialization_files_map_to_their_own_kinds() {
        assertThat(NativeImageMetadata.parse("x/jni-config.json", "[{\"name\":\"com.acme.N\"}]", "lib")
                        .of(JNI_TYPE))
                .hasSize(1);
        assertThat(NativeImageMetadata.parse("x/serialization-config.json", "[{\"name\":\"com.acme.S\"}]", "lib")
                        .of(DynamicSurface.Kind.SERIALIZATION_TYPE))
                .hasSize(1);
    }

    @Test
    void jni_members_round_trip_into_the_jni_section() {
        // A jni-config entry naming members must not drift into the reflection section: the
        // native image would then fail the JNI lookup the training run observed (JK-1779).
        String body = """
                [{"name":"com.acme.Native","methods":[{"name":"callback"}]}]
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/jni-config.json", body, "lib");

        assertThat(surface.entries()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(DynamicSurface.Kind.JNI_MEMBER);
            assertThat(e.members()).containsExactly("m:callback");
        });
        String json = ReachabilityMetadataEmitter.emit(surface);
        assertThat(json)
                .contains("\"jni\"")
                .contains("\"methods\":[{\"name\":\"callback\"}]")
                .doesNotContain("\"reflection\"");
        assertThat(KeepRuleEmitter.emit(surface)).contains("-keep class com.acme.Native { *** callback(...); }");
    }

    @Test
    void the_serialization_wrapper_form_is_unwrapped() {
        // Newer tracing agents write a map root; the legacy form is a flat array (JK-1778).
        String body = """
                {
                  "types": [{"name":"com.acme.S"}],
                  "lambdaCapturingTypes": [{"name":"com.acme.L"}],
                  "proxies": [["com.acme.I","com.acme.J"]]
                }
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/serialization-config.json", body, "train");

        assertThat(surface.of(DynamicSurface.Kind.SERIALIZATION_TYPE))
                .extracting(Entry::name)
                .containsExactly("com.acme.L", "com.acme.S");
        assertThat(surface.of(PROXY_INTERFACE)).extracting(Entry::name).containsExactly("com.acme.I,com.acme.J");
    }

    @Test
    void custom_target_constructor_class_survives_the_round_trip() {
        // The declared deserialization constructor must reach the native image, and its class's
        // constructors are invoked reflectively, so R8 keeps them too (JK-1801).
        String body = """
                [{"name":"com.acme.S","customTargetConstructorClass":"com.acme.Base"}]
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/serialization-config.json", body, "lib");

        assertThat(surface.entries()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(DynamicSurface.Kind.SERIALIZATION_TYPE);
            assertThat(e.members()).containsExactly("c:com.acme.Base");
        });
        String json = ReachabilityMetadataEmitter.emit(surface);
        assertThat(json).contains("{\"type\":\"com.acme.S\",\"customTargetConstructorClass\":\"com.acme.Base\"}");
        assertThat(KeepRuleEmitter.emit(surface)).contains("-keepclassmembers class com.acme.Base { <init>(...); }");

        DynamicSurface back = NativeImageMetadata.parse("x/reachability-metadata.json", json, "lib");
        assertThat(back.of(DynamicSurface.Kind.SERIALIZATION_TYPE))
                .singleElement()
                .satisfies(e -> assertThat(e.members()).containsExactly("c:com.acme.Base"));
    }

    @Test
    void serialization_members_still_register_the_type_for_serialization() {
        // Serialization registration is per-type in GraalVM's schema; named members must not
        // demote the entry out of the serialization section (JK-1779).
        String body = """
                [{"name":"com.acme.S","fields":[{"name":"state"}]}]
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/serialization-config.json", body, "lib");

        assertThat(surface.of(DynamicSurface.Kind.SERIALIZATION_TYPE))
                .extracting(Entry::name)
                .containsExactly("com.acme.S");
        assertThat(ReachabilityMetadataEmitter.emit(surface))
                .contains("\"serialization\"")
                .doesNotContain("\"reflection\"");
    }

    @Test
    void array_and_primitive_names_never_reach_keep_rules_verbatim() {
        // Real-world reflect-config registers arrays and primitives; `-keep class byte[]` is not
        // ProGuard syntax and aborts R8 (JK-1754). Reference arrays keep their element class;
        // primitives and primitive arrays need no keeping. The reachability output still carries
        // the original names — Graal accepts them.
        String body = """
                [
                  {"name":"byte[]"},
                  {"name":"int"},
                  {"name":"[B"},
                  {"name":"[Ljava.lang.String;"},
                  {"name":"[[Lcom/acme/Grid;"},
                  {"name":"java.lang.Object[]"}
                ]
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/reflect-config.json", body, "library:acme");

        String keeps = KeepRuleEmitter.emit(surface);
        assertThat(keeps)
                .contains("-keep class java.lang.String { *; }")
                .contains("-keep class com.acme.Grid { *; }")
                .contains("-keep class java.lang.Object { *; }")
                .doesNotContain("byte[]")
                .doesNotContain("[B")
                .doesNotContain("-keep class int");

        assertThat(ReachabilityMetadataEmitter.emit(surface))
                .contains("\"byte[]\"")
                .contains("\"[Ljava.lang.String;\"")
                .contains("\"java.lang.Object[]\"");
    }

    @Test
    void a_malformed_file_is_skipped_rather_than_failing_the_build() {
        assertThat(NativeImageMetadata.parse("x/reflect-config.json", "{ not json", "lib")
                        .entries())
                .isEmpty();
    }

    @Test
    void only_known_metadata_files_are_read() {
        assertThat(NativeImageMetadata.isMetadataFile("META-INF/native-image/g/a/reflect-config.json"))
                .isTrue();
        assertThat(NativeImageMetadata.isMetadataFile("META-INF/native-image/g/a/reachability-metadata.json"))
                .isTrue();
        // native-image.properties carries build flags, not surface; it is not ours to interpret.
        assertThat(NativeImageMetadata.isMetadataFile("META-INF/native-image/g/a/native-image.properties"))
                .isFalse();
        assertThat(NativeImageMetadata.isMetadataFile("META-INF/other/reflect-config.json"))
                .isFalse();
        assertThat(NativeImageMetadata.isMetadataFile("META-INF/native-image/g/a/"))
                .isFalse();
    }

    @Test
    void an_entry_without_a_name_is_ignored() {
        assertThat(NativeImageMetadata.parse("x/reflect-config.json", "[{\"fields\":[{\"name\":\"f\"}]}]", "lib")
                        .entries())
                .isEmpty();
    }
}
