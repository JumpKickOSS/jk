// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** {@code jk ide --print-model} output, decoded without a JSON library: the shapes the plugin meets. */
public class JkWireModelTest {

    private static final String MODEL = "{\"type\":\"ide-model-ack\",\"error\":null,\"wsRoot\":\"/w/app\","
            + "\"rootName\":\"app\",\"workspace\":true,"
            + "\"moduleDirs\":[\"/w/app/core\",\"/w/app/cli\"],\"names\":[\"core\",\"cli\"],"
            + "\"javaReleases\":[\"21\",\"21\"],\"mainClasses\":[\"\",\"cc.Main\"],"
            + "\"classesDirs\":[\"/w/app/core/target/classes\",\"/w/app/cli/target/classes\"],"
            + "\"testClassesDirs\":[\"/w/app/core/target/test-classes\",\"/w/app/cli/target/test-classes\"],"
            + "\"jdtClassesDirs\":[\"/w/app/core/target/jdt/classes/main\",\"/w/app/cli/target/jdt/classes/main\"],"
            + "\"jdtTestClassesDirs\":[\"/w/app/core/target/jdt/classes/test\",\"/w/app/cli/target/jdt/classes/test\"],"
            + "\"genSrcDirs\":[\"/w/app/target/core/gen/main\",\"/w/app/target/cli/gen/main\"],"
            + "\"genTestSrcDirs\":[\"/w/app/target/core/gen/test\",\"/w/app/target/cli/gen/test\"],"
            + "\"libNames\":[\"g:a:jar::1\"],\"libFiles\":[\"g_a_jar__1\"],\"libJars\":[\"/s/a.jar\"],\"libSources\":[\"\"],"
            + "\"siblingRefs\":[\"1|core|COMPILE\"],\"libEntries\":[\"0|g:a:jar::1|MAIN,TEST\",\"1|g:a:jar::1|TEST\"],"
            + "\"processorJars\":[\"1|/s/p.jar\"],"
            + "\"sdkStableNames\":[\"temurin-21\",\"temurin-21\"],\"sdkNames\":[\"jk-temurin-21\",\"jk-temurin-21\"],"
            + "\"sdkLevels\":[\"21\",\"21\"],\"sdkHomes\":[\"/j/21\",\"/j/21\"],\"sdkVersions\":[\"21.0.1\",\"21.0.1\"],"
            + "\"defSdkStableName\":\"temurin-21\",\"defSdkName\":\"jk-temurin-21\",\"defSdkLevel\":21,"
            + "\"defSdkHome\":\"/j/21\",\"defSdkVersion\":\"21.0.1\",\"sdkEntries\":[\"jk-temurin-21|/j/21|21.0.1\"]}";

    @Test
    public void a_full_model_decodes_every_field() {
        JkWireModel m = JkWireModel.parse(MODEL);
        assertNull(m.error);
        assertEquals("/w/app", m.wsRoot);
        assertEquals("app", m.rootName);
        assertTrue(m.workspace);
        assertEquals(2, m.moduleCount());
        assertEquals(List.of("/w/app/core", "/w/app/cli"), m.moduleDirs());

        JkWireModel.Module cli = m.modules.get(1);
        assertEquals("cli", cli.name());
        assertEquals(21, cli.javaRelease());
        assertEquals("cc.Main", cli.mainClass());
        assertNull(m.modules.get(0).mainClass());
        assertEquals("/w/app/cli/target/jdt/classes/main", cli.jdtClassesDir());
        assertEquals("/w/app/cli/target/jdt/classes/test", cli.jdtTestClassesDir());
        assertEquals("/w/app/target/cli/gen/main", cli.genSrcDir());
        assertEquals(new JkWireModel.Sdk("temurin-21", "jk-temurin-21", 21, "/j/21", "21.0.1"), cli.sdk());

        assertEquals(List.of(new JkWireModel.Lib("g:a:jar::1", "g_a_jar__1", "/s/a.jar", null)), m.libs);
        assertEquals(List.of(new JkWireModel.SiblingRef(1, "core", "COMPILE")), m.siblingRefs);
        assertEquals(
                List.of(
                        new JkWireModel.LibEntry(0, "g:a:jar::1", List.of("MAIN", "TEST")),
                        new JkWireModel.LibEntry(1, "g:a:jar::1", List.of("TEST"))),
                m.libEntries);
        assertEquals(List.of(new JkWireModel.ProcessorJar(1, "/s/p.jar")), m.processorJars);
        assertEquals(new JkWireModel.Sdk("temurin-21", "jk-temurin-21", 21, "/j/21", "21.0.1"), m.defaultSdk);
        assertEquals(List.of(new JkWireModel.SdkEntry("jk-temurin-21", "/j/21", "21.0.1")), m.sdkEntries);
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
        JkWireModel failed = JkWireModel.parse("{\"type\":\"ide-model-ack\",\"error\":\"no jk.toml in /w\"}");
        assertEquals("no jk.toml in /w", failed.error);
        assertNull(JkWireModel.parse(MODEL).error);
    }

    @Test
    public void escapes_in_strings_are_decoded() {
        JkWireModel m = JkWireModel.parse(
                "{\"wsRoot\":\"C:\\\\w\\\\my \\\"app\\\"\",\"moduleDirs\":[\"C:\\\\w\\\\my \\\"app\\\"\\\\a\"],\"names\":[\"a\\nb\"]}");
        assertEquals("C:\\w\\my \"app\"", m.wsRoot);
        assertEquals("a\nb", m.modules.get(0).name());
    }

    @Test
    public void missing_fields_read_as_empty_not_null() {
        JkWireModel m = JkWireModel.parse("{}");
        assertEquals("", m.wsRoot);
        assertEquals("", m.rootName);
        assertFalse(m.workspace);
        assertEquals(0, m.moduleCount());
        assertEquals(List.of(), m.libs);
        assertEquals("", m.defaultSdk.name());
        assertNull(m.error);
    }

    @Test
    public void a_short_parallel_array_reads_as_empty_cells() {
        JkWireModel m = JkWireModel.parse("{\"moduleDirs\":[\"/a\",\"/b\"],\"names\":[\"a\"]}");
        assertEquals("a", m.modules.get(0).name());
        assertEquals("", m.modules.get(1).name());
        assertEquals(0, m.modules.get(1).sdk().level());
    }

    @Test
    public void no_object_on_stdout_is_an_error() {
        assertThrows(IllegalArgumentException.class, () -> JkWireModel.parse("engine could not start\n"));
    }
}
