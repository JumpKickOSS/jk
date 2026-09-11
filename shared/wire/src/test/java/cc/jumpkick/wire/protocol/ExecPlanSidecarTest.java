// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** A sidecar on the wire carries every field; a plan missing one is malformed, not defaulted. */
class ExecPlanSidecarTest {

    private static final ExecPlan.Sidecar WEB = new ExecPlan.Sidecar(
            "web",
            List.of("npm", "run", "dev"),
            "/w/web",
            Map.of("PORT", "5173"),
            "http://localhost:5173",
            "",
            60_000L,
            true,
            JkBuild.SidecarRestart.ON_EXIT);

    @Test
    void every_field_round_trips_and_restart_is_spelled_as_in_the_manifest() {
        String encoded = WEB.encode();
        assertThat(encoded).contains("\"restart\":\"on-exit\"");
        assertThat(ExecPlan.Sidecar.decode(encoded)).isEqualTo(WEB);
    }

    @Test
    void a_missing_field_is_a_malformed_plan() {
        String encoded = WEB.encode();
        for (String key : List.of(
                "name",
                "command",
                "cwd",
                "env",
                "ready",
                "readyPattern",
                "readyTimeoutMillis",
                "frontDoor",
                "restart")) {
            String without = Pattern.compile("\"" + key + "\":(\"[^\"]*\"|\\[[^]]*]|\\{[^}]*}|-?\\d+|true|false),?")
                    .matcher(encoded)
                    .replaceFirst("");
            assertThat(without).as("removed " + key).isNotEqualTo(encoded);
            assertThatThrownBy(() -> ExecPlan.Sidecar.decode(without))
                    .as("without " + key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(key);
        }
    }

    @Test
    void the_record_owns_copies_of_its_collections() {
        var command = new ArrayList<>(List.of("npm"));
        var env = new LinkedHashMap<>(Map.of("A", "1"));
        ExecPlan.Sidecar s =
                new ExecPlan.Sidecar("web", command, "/w", env, "", "", 1L, false, JkBuild.SidecarRestart.NEVER);
        command.add("run");
        env.put("B", "2");
        assertThat(s.command()).containsExactly("npm");
        assertThat(s.env()).containsOnlyKeys("A");
    }
}
