// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.ProjectFacts;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The exact bytes of the spec and reply lines whose fields are optional, so the field order and omissions hold. */
class WireLineBytesTest {

    @Test
    void aDiagnosticOmitsTheFieldsItDoesNotKnow() {
        assertThat(PluginReply.diagnostic("error", null, 0, 0, "boom", ""))
                .isEqualTo("{\"t\":\"diagnostic\",\"sev\":\"error\",\"msg\":\"boom\"}");
        assertThat(PluginReply.diagnostic("warning", "A.java", 3, 7, "say \"x\"", "compiler.warn.x"))
                .isEqualTo("{\"t\":\"diagnostic\",\"sev\":\"warning\",\"file\":\"A.java\",\"line\":3,"
                        + "\"col\":7,\"key\":\"compiler.warn.x\",\"msg\":\"say \\\"x\\\"\"}");
    }

    @Test
    void aFindingCarriesFixedInAndAliasesOnlyWhenKnown() {
        assertThat(PluginReply.finding("g:a", "1", "CVE-1", "HIGH", "s", null, List.of()))
                .isEqualTo("{\"t\":\"finding\",\"module\":\"g:a\",\"version\":\"1\",\"id\":\"CVE-1\","
                        + "\"severity\":\"HIGH\",\"summary\":\"s\"}");
        assertThat(PluginReply.finding("g:a", "1", "CVE-1", "HIGH", "s", "2", List.of("GHSA-1")))
                .endsWith(",\"summary\":\"s\",\"fixedIn\":\"2\",\"aliases\":[\"GHSA-1\"]}");
    }

    @Test
    void aResultSerializesEachValueByShape() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ok", true);
        fields.put("n", 2);
        fields.put("names", List.of("a"));
        fields.put("why", "w");
        assertThat(PluginReply.result(fields))
                .isEqualTo("{\"t\":\"result\",\"ok\":true,\"n\":2,\"names\":[\"a\"],\"why\":\"w\"}");
        assertThat(PluginReply.ready()).isEqualTo("{\"t\":\"ready\"}");
    }

    @Test
    void specLinesKeepTheirFieldOrder() {
        Path jar = Path.of("lib.jar").toAbsolutePath();
        List<String> lines = new SpecWriter()
                .op("run-step", null, "p")
                .offline(false)
                .project(new ProjectFacts("g", "n", "1", 25, " ", false, true, Map.of("K", "V")))
                .entry("lib.jar", jar, true, null, "g", "a", "1")
                .lines();
        assertThat(lines)
                .containsExactly(
                        "{\"t\":\"op\",\"op\":\"run-step\",\"plugin\":\"p\"}",
                        "{\"t\":\"offline\",\"value\":false}",
                        "{\"t\":\"project\",\"group\":\"g\",\"name\":\"n\",\"version\":\"1\",\"javaRelease\":25,"
                                + "\"nativeDeclared\":false,\"kotlin\":true}",
                        "{\"t\":\"manifest-attr\",\"key\":\"K\",\"value\":\"V\"}",
                        "{\"t\":\"entry\",\"file\":\"lib.jar\",\"group\":\"g\",\"artifact\":\"a\",\"version\":\"1\","
                                + "\"path\":" + quoted(jar) + ",\"snapshot\":true}");
    }

    private static String quoted(Path path) {
        return Jsonl.quote(path.toString());
    }
}
