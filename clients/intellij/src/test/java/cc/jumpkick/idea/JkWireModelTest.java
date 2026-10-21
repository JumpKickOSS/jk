// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code jk ide -p} output, decoded without a JSON library: the shapes the plugin meets. */
class JkWireModelTest {

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
    void a_full_model_decodes_every_field() {
        JkWireModel m = JkWireModel.parse(MODEL);
        assertThat(m.error).isNull();
        assertThat(m.wsRoot).isEqualTo("/w/app");
        assertThat(m.rootName).isEqualTo("app");
        assertThat(m.workspace).isTrue();
        assertThat(m.moduleCount()).isEqualTo(2);
        assertThat(m.moduleDirs()).isEqualTo(List.of("/w/app/core", "/w/app/cli"));

        JkWireModel.Module cli = m.modules.get(1);
        assertThat(cli.name()).isEqualTo("cli");
        assertThat(cli.javaRelease()).isEqualTo(21);
        assertThat(cli.mainClass()).isEqualTo("cc.Main");
        assertThat(m.modules.get(0).mainClass()).isNull();
        assertThat(cli.jdtClassesDir()).isEqualTo("/w/app/cli/target/jdt/classes/main");
        assertThat(cli.jdtTestClassesDir()).isEqualTo("/w/app/cli/target/jdt/classes/test");
        assertThat(cli.genSrcDir()).isEqualTo("/w/app/target/cli/gen/main");
        assertThat(cli.sdk()).isEqualTo(new JkWireModel.Sdk("temurin-21", "jk-temurin-21", 21, "/j/21", "21.0.1"));

        assertThat(m.libs).isEqualTo(List.of(new JkWireModel.Lib("g:a:jar::1", "g_a_jar__1", "/s/a.jar", null)));
        assertThat(m.siblingRefs).isEqualTo(List.of(new JkWireModel.SiblingRef(1, "core", "COMPILE")));
        assertThat(m.libEntries)
                .isEqualTo(List.of(
                        new JkWireModel.LibEntry(0, "g:a:jar::1", List.of("MAIN", "TEST")),
                        new JkWireModel.LibEntry(1, "g:a:jar::1", List.of("TEST"))));
        assertThat(m.processorJars).isEqualTo(List.of(new JkWireModel.ProcessorJar(1, "/s/p.jar")));
        assertThat(m.defaultSdk).isEqualTo(new JkWireModel.Sdk("temurin-21", "jk-temurin-21", 21, "/j/21", "21.0.1"));
        assertThat(m.sdkEntries).isEqualTo(List.of(new JkWireModel.SdkEntry("jk-temurin-21", "/j/21", "21.0.1")));
    }

    @Test
    void chrome_before_the_object_on_stdout_is_ignored() {
        JkWireModel m = JkWireModel.parse("jk: engine warming up\n" + MODEL + "\n");
        assertThat(m.rootName).isEqualTo("app");
        assertThat(m.moduleCount()).isEqualTo(2);
    }

    @Test
    void the_last_typed_object_wins_when_several_leak() {
        String earlier = "{\"type\":\"progress\",\"rootName\":\"stale\"}\n";
        assertThat(JkWireModel.parse(earlier + MODEL).rootName).isEqualTo("app");
    }

    @Test
    void an_error_field_is_surfaced_and_a_json_null_is_absent() {
        JkWireModel failed = JkWireModel.parse("{\"type\":\"ide-model-ack\",\"error\":\"no jk.toml in /w\"}");
        assertThat(failed.error).isEqualTo("no jk.toml in /w");
        assertThat(JkWireModel.parse(MODEL).error).isNull();
    }

    @Test
    void escapes_in_strings_are_decoded() {
        JkWireModel m = JkWireModel.parse(
                "{\"wsRoot\":\"C:\\\\w\\\\my \\\"app\\\"\",\"moduleDirs\":[\"C:\\\\w\\\\my \\\"app\\\"\\\\a\"],\"names\":[\"a\\nb\"]}");
        assertThat(m.wsRoot).isEqualTo("C:\\w\\my \"app\"");
        assertThat(m.modules.get(0).name()).isEqualTo("a\nb");
    }

    @Test
    void missing_fields_read_as_empty_not_null() {
        JkWireModel m = JkWireModel.parse("{}");
        assertThat(m.wsRoot).isEqualTo("");
        assertThat(m.rootName).isEqualTo("");
        assertThat(m.workspace).isFalse();
        assertThat(m.moduleCount()).isEqualTo(0);
        assertThat(m.libs).isEqualTo(List.of());
        assertThat(m.defaultSdk.name()).isEqualTo("");
        assertThat(m.error).isNull();
    }

    @Test
    void a_short_parallel_array_reads_as_empty_cells() {
        JkWireModel m = JkWireModel.parse("{\"moduleDirs\":[\"/a\",\"/b\"],\"names\":[\"a\"]}");
        assertThat(m.modules.get(0).name()).isEqualTo("a");
        assertThat(m.modules.get(1).name()).isEqualTo("");
        assertThat(m.modules.get(1).sdk().level()).isEqualTo(0);
    }

    @Test
    void no_object_on_stdout_is_an_error() {
        assertThatThrownBy(() -> JkWireModel.parse("engine could not start\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
