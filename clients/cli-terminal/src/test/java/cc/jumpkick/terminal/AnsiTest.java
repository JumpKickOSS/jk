// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AnsiTest {
    @Test
    void oscConstructorsAlwaysEmit() {
        assertThat(Ansi.taskbarProgress(40)).contains("9;4;1;40");
        assertThat(Ansi.TASKBAR_INDETERMINATE).isEqualTo(Ansi.taskbarIndeterminate());
        assertThat(Ansi.windowTitle("hi")).contains("hi").doesNotContain("\n");
        assertThat(Ansi.desktopNotify("JumpKick Build", "hello"))
                .contains("i=jk")
                .contains("hello");
    }

    @Test
    void noSessionContextImport() throws Exception {
        Path p = Path.of("src/main/java/cc/jumpkick/terminal/Ansi.java");
        if (!Files.exists(p)) {
            p = Path.of("clients/cli-terminal/src/main/java/cc/jumpkick/terminal/Ansi.java");
        }
        String src = Files.readString(p);
        assertThat(src).doesNotContain("import cc.jumpkick.");
        assertThat(src).doesNotContain("SessionContext.current");
    }
}
