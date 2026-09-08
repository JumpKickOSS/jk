// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.ide.IdeEngineClient;
import cc.jumpkick.model.JkVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The BSP result shapes as they were when built by concatenation; an IDE parses these bytes. */
class BspJsonFrozenBytesTest {
    @Test
    void initialize_result_names_the_three_providers() {
        String provider = "{\"languageIds\":[\"java\",\"kotlin\",\"groovy\"]}";
        assertThat(BspServer.initializeResultJson())
                .isEqualTo("{\"displayName\":\"jk\",\"version\":\"" + JkVersion.VERSION + "\",\"bspVersion\":\"2.1.0\","
                        + "\"capabilities\":{\"compileProvider\":" + provider + ",\"testProvider\":" + provider
                        + ",\"runProvider\":" + provider + ",\"canReload\":true}}");
    }

    @Test
    void build_target_shape() {
        assertThat(BspServer.targetJson("file:///p#app", "g:app", "file:///p/app", true))
                .isEqualTo(
                        "{\"id\":{\"uri\":\"file:///p#app\"},\"displayName\":\"g:app\",\"baseDirectory\":\"file:///p/app\","
                                + "\"tags\":[\"library\"],\"languageIds\":[\"java\",\"kotlin\",\"groovy\"],\"dependencies\":[],"
                                + "\"capabilities\":{\"canCompile\":true,\"canTest\":true,\"canRun\":true}}");
    }

    @Test
    void diagnostic_and_status_shapes() {
        assertThat(BspServer.diagnosticJson(4, 2, 1, "cannot find symbol \"x\""))
                .isEqualTo("{\"range\":{\"start\":{\"line\":4,\"character\":2},\"end\":{\"line\":4,\"character\":2}},"
                        + "\"severity\":1,\"message\":\"cannot find symbol \\\"x\\\"\"}");
        var green = new IdeEngineClient.BuildOutcome(true, 1, 0, List.of(), List.of());
        var red = new IdeEngineClient.BuildOutcome(false, 1, 1, List.of("a", "b"), List.of());
        var redSilent = new IdeEngineClient.BuildOutcome(false, 1, 1, List.of(), List.of());
        assertThat(BspServer.statusResult(green, "compile failed")).isEqualTo("{\"statusCode\":1}");
        assertThat(BspServer.statusResult(red, "compile failed")).isEqualTo("{\"statusCode\":2,\"message\":\"a; b\"}");
        assertThat(BspServer.statusResult(redSilent, "compile failed"))
                .isEqualTo("{\"statusCode\":2,\"message\":\"compile failed\"}");
    }
}
