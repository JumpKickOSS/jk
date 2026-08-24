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
        String src = Files.readString(ansiSource());
        assertThat(src).doesNotContain("import cc.jumpkick.");
        assertThat(src).doesNotContain("SessionContext.current");
    }

    /**
     * Workspace {@code jk build} runs tests with CWD at {@code ~/.local/state/jk/engine}, so
     * {@code src/...} relatives miss. Walk from this class's output location (and CWD) instead.
     */
    static Path ansiSource() throws Exception {
        Path rel = Path.of("src/main/java/cc/jumpkick/terminal/Ansi.java");
        Path classLoc = Path.of(AnsiTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toAbsolutePath()
                .normalize();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (Path start : new Path[] {classLoc, cwd}) {
            for (Path d = start; d != null; d = d.getParent()) {
                Path atModule = d.resolve(rel);
                if (Files.isRegularFile(atModule)) {
                    return atModule;
                }
                Path atWorkspace = d.resolve("clients/cli-terminal").resolve(rel);
                if (Files.isRegularFile(atWorkspace)) {
                    return atWorkspace;
                }
            }
        }
        throw new AssertionError("cannot locate Ansi.java from class=" + classLoc + " cwd=" + cwd);
    }
}
