// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Micronaut plugin's native path end to end: the bundled template, a declared `[native]`
 * table, and `jk native` producing an executable through the plugin's contributed
 * native-image arguments and the native AOT runtime. Needs Maven Central for the Micronaut
 * closure and a GraalVM with native-image, so it rides the nightly network tier.
 */
@Tag("network")
class MicronautNativeSmokeTest {

    @Test
    void a_micronaut_hello_service_builds_to_a_native_executable(@TempDir Path tempDir) throws Exception {
        String graal = System.getenv("GRAALVM_HOME");
        assumeTrue(
                graal != null && Files.isExecutable(Path.of(graal, "bin", "native-image")),
                "GRAALVM_HOME with native-image is what this smoke exercises");
        Path project = tempDir.resolve("mnhello");
        // The plugin's bundled Giter8 tree, by path: the short name would go through the template
        // store, which this sandbox does not populate and which a nightly must not reach GitHub for.
        Path template = RepoRoot.find(MicronautNativeSmokeTest.class)
                .resolve("plugins/micronaut/src/main/resources/templates/java/micronaut/hello.g8");
        assertThat(run("new", "-t", template.toString(), "--name", "mnhello", project.toString()))
                .isEqualTo(0);
        // A template applies its own manifest, so [native] is declared here, the way a user would.
        Files.writeString(project.resolve("jk.toml"), "\n[native]\n", StandardOpenOption.APPEND);

        int exit = run(
                "native",
                "-C",
                project.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());

        assertThat(exit).isEqualTo(0);
        Path exe = project.resolve("target/mnhello");
        assertThat(exe).as("executable at target/<name>").exists();
        assertThat(Files.isExecutable(exe)).isTrue();
    }
}
