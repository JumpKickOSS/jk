// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The request's toolchain selection survives the wire.
 *
 * <p>It did not, and nothing noticed: {@code --jdk} and {@code --graal} were set on the client's
 * {@code Session} and never serialised, so every engine-side resolver walked its {@code SWITCH} tier
 * against an empty selection and fell through to whichever JDK the shell that started the daemon
 * happened to name. The symptom was not a missing override but a build whose JDK depended on how the
 * daemon had been launched (JK-1021).
 */
class ProtoSessionToolchainTest {

    @Test
    void selection_rides_the_request_and_decodes_back() {
        String line = ProtoSession.withToolchain("{}", "temurin-21", "graal-25", "/opt/graal-25");

        assertThat(ProtoSession.jdkSpecOf(line)).isEqualTo("temurin-21");
        assertThat(ProtoSession.graalSpecOf(line)).isEqualTo("graal-25");
        assertThat(ProtoSession.graalHomeOf(line)).isEqualTo(Path.of("/opt/graal-25"));
    }

    @Test
    void nothing_selected_leaves_the_line_untouched() {
        // An unadorned request must stay byte-identical, or every request grows a field that means
        // "no opinion" and the envelope stops being free.
        assertThat(ProtoSession.withToolchain("{}", null, null, null)).isEqualTo("{}");
        assertThat(ProtoSession.withToolchain("{}", "  ", "", "  ")).isEqualTo("{}");
        assertThat(ProtoSession.jdkSpecOf("{}")).isNull();
        assertThat(ProtoSession.graalSpecOf("{}")).isNull();
        assertThat(ProtoSession.graalHomeOf("{}")).isNull();
    }

    @Test
    void one_half_of_the_selection_is_a_valid_request() {
        String jdkOnly = ProtoSession.withToolchain("{}", "temurin-21", null, null);
        assertThat(ProtoSession.jdkSpecOf(jdkOnly)).isEqualTo("temurin-21");
        assertThat(ProtoSession.graalSpecOf(jdkOnly)).isNull();
    }

    @Test
    void it_composes_with_the_rest_of_the_session_envelope() {
        // The two splices are independent, so applying both must not corrupt either. This is the
        // shape the CLI actually sends.
        String line = ProtoSession.withToolchain(
                ProtoSession.withSession("{\"dir\":\"/w\"}", "alpha", Map.of("PATH", "/usr/bin"), null, true, false),
                "temurin-21",
                "graal-25",
                "/opt/graal-25");

        assertThat(ProtoSession.jdkSpecOf(line)).isEqualTo("temurin-21");
        assertThat(ProtoSession.graalSpecOf(line)).isEqualTo("graal-25");
        assertThat(ProtoSession.graalHomeOf(line)).isEqualTo(Path.of("/opt/graal-25"));
        assertThat(ProtoSession.variantOf(line)).isEqualTo("alpha");
        assertThat(ProtoSession.clientEnvOf(line)).containsEntry("PATH", "/usr/bin");
    }
}
