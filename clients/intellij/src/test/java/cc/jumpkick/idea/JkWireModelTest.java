// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** {@code jk ide --print-model} output, decoded without a JSON library: the shapes the plugin meets. */
public class JkWireModelTest {

    private static final String MODEL = "{\"type\":\"ide-model\",\"error\":null,\"wsRoot\":\"/w/app\","
            + "\"rootName\":\"app\",\"workspace\":true,"
            + "\"moduleDirs\":[\"/w/app/core\",\"/w/app/cli\"],\"names\":[\"core\",\"cli\"],"
            + "\"javaReleases\":[\"21\",\"21\"],\"mainClasses\":[\"\",\"cc.Main\"],"
            + "\"libJars\":[\"/s/a.jar\"],\"libSources\":[]}";

    @Test
    public void a_full_model_decodes_every_field() {
        JkWireModel m = JkWireModel.parse(MODEL);
        assertNull(m.error);
        assertEquals("/w/app", m.wsRoot);
        assertEquals("app", m.rootName);
        assertTrue(m.workspace);
        assertEquals(List.of("/w/app/core", "/w/app/cli"), m.moduleDirs);
        assertEquals(List.of("core", "cli"), m.names);
        assertEquals(List.of("21", "21"), m.javaReleases);
        assertEquals(List.of("", "cc.Main"), m.mainClasses);
        assertEquals(List.of("/s/a.jar"), m.libJars);
        assertEquals(List.of(), m.libSources);
        assertEquals(2, m.moduleCount());
    }

    @Test
    public void chrome_before_the_object_on_stdout_is_ignored() {
        JkWireModel m = JkWireModel.parse("jk: engine warming up\n" + MODEL + "\n");
        assertEquals("app", m.rootName);
        assertEquals(2, m.moduleCount());
    }

    @Test
    public void the_last_typed_object_wins_when_several_leak() {
        String earlier = "{\"type\":\"progress\",\"rootName\":\"stale\"}\n";
        assertEquals("app", JkWireModel.parse(earlier + MODEL).rootName);
    }

    @Test
    public void an_error_field_is_surfaced_and_a_json_null_is_absent() {
        JkWireModel failed = JkWireModel.parse("{\"type\":\"ide-model\",\"error\":\"no jk.toml in /w\"}");
        assertEquals("no jk.toml in /w", failed.error);
        assertNull(JkWireModel.parse(MODEL).error);
    }

    @Test
    public void escapes_in_strings_are_decoded() {
        JkWireModel m =
                JkWireModel.parse("{\"wsRoot\":\"C:\\\\w\\\\my \\\"app\\\"\",\"names\":[\"a\\nb\",\"t\\tab\"]}");
        assertEquals("C:\\w\\my \"app\"", m.wsRoot);
        assertEquals(List.of("a\nb", "t\tab"), m.names);
    }

    @Test
    public void missing_fields_read_as_empty_not_null() {
        JkWireModel m = JkWireModel.parse("{}");
        assertEquals("", m.wsRoot);
        assertEquals("", m.rootName);
        assertFalse(m.workspace);
        assertEquals(List.of(), m.moduleDirs);
        assertEquals(0, m.moduleCount());
        assertNull(m.error);
    }

    @Test
    public void whitespace_around_colons_and_commas_is_tolerated() {
        JkWireModel m =
                JkWireModel.parse("{ \"workspace\" : true , \"names\" : [ \"x\" , \"y\" ] , \"rootName\" : \"r\" }");
        assertTrue(m.workspace);
        assertEquals(List.of("x", "y"), m.names);
        assertEquals("r", m.rootName);
    }
}
