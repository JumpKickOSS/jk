// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildLogicGroovyHostTest {

    @Test
    void wrap_points_the_child_at_the_user_script_with_escaped_paths(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("after-resources.groovy");
        Files.writeString(script, "outDir.resolve('x.txt').toFile().text = 'ok'\n");
        Path project = dir.resolve("proj");
        Path out = dir.resolve("o");
        String wrapped = BuildLogicGroovyHost.wrap(script, project, out);
        assertTrue(wrapped.contains("setVariable('projectDir'"));
        assertTrue(wrapped.contains("setVariable('outDir'"));
        assertTrue(wrapped.contains("setVariable('properties'"));
        assertTrue(wrapped.contains("GroovyShell"));
        assertTrue(wrapped.contains(BuildLogicGroovyHost.groovyString(
                script.toAbsolutePath().normalize().toString())));
        assertTrue(wrapped.contains(BuildLogicGroovyHost.groovyString(
                project.toAbsolutePath().normalize().toString())));
    }

    @Test
    void groovyString_escapes_quotes_and_backslashes() {
        assertTrue(BuildLogicGroovyHost.groovyString("a'b\\c").contains("\\'"));
        assertTrue(BuildLogicGroovyHost.groovyString("a'b\\c").contains("\\\\"));
    }
}
