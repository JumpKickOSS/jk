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
        String body = """
                {
                  "reflection": [{"type":"com.acme.R","allDeclaredMethods":true}],
                  "jni": [{"type":"com.acme.N"}],
                  "reflection-proxies": [{"interfaces":["com.acme.I","com.acme.J"]}],
                  "resources": {"includes":[{"glob":"config/*.yml"}]}
                }
                """;

        DynamicSurface surface = NativeImageMetadata.parse("x/reachability-metadata.json", body, "library:acme");

        assertThat(surface.of(REFLECTIVE_TYPE)).extracting(Entry::name).containsExactly("com.acme.R");
        assertThat(surface.of(JNI_TYPE)).extracting(Entry::name).containsExactly("com.acme.N");
        assertThat(surface.of(PROXY_INTERFACE)).extracting(Entry::name).containsExactly("com.acme.I", "com.acme.J");
        assertThat(surface.of(RESOURCE)).extracting(Entry::name).containsExactly("config/*.yml");
    }

    @Test
    void the_split_resource_schema_reads_patterns() {
        String body = """
                {"resources":{"includes":[{"pattern":"\\\\Qapplication.yml\\\\E"}]}}
                """;

        assertThat(NativeImageMetadata.parse("x/resource-config.json", body, "lib")
                        .of(RESOURCE))
                .extracting(Entry::name)
                .containsExactly("\\Qapplication.yml\\E");
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
