// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An {@code [application]} key written at the top level is a misplacement, and must say so.
 *
 * <p>It used to be silent for {@code assembly}: the manifest parsed, the fat jar was never built,
 * and {@code jk install}'s ladder — native, minified, fat, thin — then installed a thin-jar
 * launcher because no fat jar existed. Every step honest, the whole outcome wrong, and nothing
 * printed (JK-1073). {@code minified} did stop the build, but reported a type problem for what is
 * a wrong-table problem.
 */
class ManifestFlattenedApplicationKeyTest {

    @TempDir
    Path tmp;

    private Path manifest(String body) throws Exception {
        Path f = tmp.resolve("jk.toml");
        Files.writeString(f, "group = \"ex\"\nname = \"demo\"\nversion = \"1.0\"\njava = 25\n" + body);
        return f;
    }

    @Test
    void every_application_key_is_rejected_at_the_top_level() throws Exception {
        for (String key : new String[] {"assembly", "minified", "native", "config", "main"}) {
            String value = "main".equals(key) || "config".equals(key) ? "\"x\"" : "true";
            Path f = manifest(key + " = " + value + "\n\n[application]\nmain = \"ex.Main\"\n");
            assertThatThrownBy(() -> JkBuildParser.parse(f))
                    .as("top-level `%s` must not be silently ignored", key)
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining(key)
                    .hasMessageContaining("[application]");
        }
    }

    @Test
    void the_correct_spelling_still_works() throws Exception {
        Path f = manifest("[application]\nmain = \"ex.Main\"\nassembly = true\nminified = true\n");
        var build = JkBuildParser.parse(f);
        assertThat(build.assembly()).isTrue();
        assertThat(build.minified()).isTrue();
    }

    @Test
    void legitimate_top_level_tables_of_the_same_name_are_untouched() throws Exception {
        // [native] and [config] are real top-level tables; only the bare scalar form can only be a
        // misplacement, so the guard is on scalars and must not fire here.
        Path f = manifest("[application]\nmain = \"ex.Main\"\n\n[native]\nenabled = true\n");
        assertThatCode(() -> JkBuildParser.parse(f)).doesNotThrowAnyException();
    }
}
