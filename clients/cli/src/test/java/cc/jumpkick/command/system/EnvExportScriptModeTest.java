// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.model.command.Invocation;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code eval "$(jk env -e)"}: stdout is a script, so dispatch must begin it in script mode — no
 * envelope blank lines and no ASCII rewrite of the values. This replays what dispatch does with
 * the command's own {@code scriptMode} answer, under the plain mode CI / TERM=dumb / -u selects.
 */
class EnvExportScriptModeTest {

    private static final Invocation EXPORT =
            Invocation.builder().flag("export", true).build();

    @Test
    void env_export_is_script_mode() {
        assertThat(new EnvCommand().scriptMode(EXPORT))
                .as("EnvCommand.scriptMode(-e)")
                .isTrue();
    }

    @Test
    void env_export_bytes_are_exactly_the_script_in_plain_mode() throws Exception {
        String script = EnvCommand.exportLines(Map.of("FOO", "a…b"), name -> null, false);
        String out = NoAnsi.forced(() -> Capture.stdout(() -> {
            CliOutput.beginCommand(new EnvCommand().scriptMode(EXPORT));
            try {
                CliOutput.outRaw(script);
                CliOutput.closeEnvelope();
            } finally {
                CliOutput.beginCommand(false);
            }
        }));
        assertThat(Capture.lf(out)).as("stdout of jk env -e").isEqualTo("export FOO='a…b'\n");
    }
}
