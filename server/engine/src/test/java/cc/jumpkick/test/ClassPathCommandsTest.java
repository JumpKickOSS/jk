// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Classpaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A command runs the test JVM's workspace classes when it names one of its class trees or workspace
 * jars, expands what no command line shows, or is a program the test wrote; anything else (git, node,
 * setsid around a worker JVM) runs none of them.
 */
class ClassPathCommandsTest {

    @Test
    void a_command_naming_the_test_class_path_reaches_it(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path module = Files.createDirectories(ws.resolve("server/engine"));
        Path classes = Files.createDirectories(module.resolve("target/classes"));
        Path sibling = Files.createDirectories(ws.resolve("shared/core/target")).resolve("core.jar");
        Path store = tmp.resolve("store/lib.jar");
        Path temp = Files.createDirectories(tmp.resolve("temp"));
        ClassReads.Layout layout = new ClassReads.Layout(
                List.of(classes), List.of(), Set.of(sibling, store), ws, null, List.of(), List.of(temp));
        String sep = Classpaths.SEPARATOR;

        assertThat(reaches("/opt/jdk/bin/java -cp " + store + sep + classes + " demo.Main", module, layout))
                .as("java on a class tree")
                .isTrue();
        assertThat(reaches("java -cp target/classes demo.Main", module, layout))
                .as("a class tree named relative to the JVM's directory")
                .isTrue();
        assertThat(reaches("java -Djava.class.path=" + sibling + " demo.Main", module, layout))
                .as("a workspace jar on the class path")
                .isTrue();
        assertThat(reaches("java " + classes.resolve("demo/Main.class"), module, layout))
                .as("a file under a class tree")
                .isTrue();
        assertThat(reaches("/usr/bin/setsid java @" + temp.resolve("args"), module, layout))
                .as("an argument file no command line shows")
                .isTrue();
        assertThat(reaches("sh -c java -cp $CP demo.Main", module, layout))
                .as("a variable")
                .isTrue();
        assertThat(reaches(temp.resolve("helper.sh") + " run", module, layout))
                .as("a program the test wrote")
                .isTrue();

        assertThat(reaches("/usr/bin/setsid /opt/jdk/bin/java -cp " + store + " Worker", module, layout))
                .as("a worker JVM on a jar outside the workspace")
                .isFalse();
        assertThat(reaches("git -C " + ws + " status", module, layout))
                .as("git in the checkout")
                .isFalse();
        assertThat(reaches("node " + module.resolve("web/build.mjs"), module, layout))
                .as("node on a script")
                .isFalse();
        assertThat(reaches("tar -C " + module.resolve("target") + " -xf x.tgz", module, layout))
                .as("the module's build output above its class tree")
                .isFalse();
    }

    @Test
    void the_workspace_files_a_command_names_are_its_reads(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path module = Files.createDirectories(ws.resolve("web"));
        Path script = Files.writeString(module.resolve("build.mjs"), "x");
        Path temp = Files.createDirectories(tmp.resolve("temp"));
        Path made = Files.writeString(temp.resolve("made.txt"), "x");
        Path outside = Files.writeString(tmp.resolve("outside.txt"), "x");
        ClassReads.Layout layout =
                new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, null, List.of(), List.of(temp));

        assertThat(ClassPathCommands.named(
                        "node build.mjs --out=" + made + " " + outside + " missing.js -v", null, module, layout))
                .as("a file of the workspace by its path, not one a test made, one outside or one absent")
                .containsExactly(script);
    }

    private static boolean reaches(String command, Path cwd, ClassReads.Layout layout) {
        return ClassPathCommands.reaches(command, null, cwd, layout);
    }
}
