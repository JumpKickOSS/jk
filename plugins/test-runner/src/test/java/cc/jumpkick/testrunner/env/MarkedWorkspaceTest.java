// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The probe marks the classes of a module's class trees and of its siblings' jars, which the cones
 * key class by class, and leaves a library's jar as compiled: the suite's key holds that whole. The
 * workspace rides the agent argument with its length, so a path holding a space or a colon reads
 * back whole.
 */
class MarkedWorkspaceTest {

    @Test
    void a_class_tree_and_a_jar_inside_the_workspace_are_marked_a_library_jar_is_not(@TempDir Path tmp)
            throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path sibling = Files.createDirectories(ws.resolve("lib/target")).resolve("lib.jar");
        Files.write(sibling, new byte[0]);
        Path library = Files.createDirectories(tmp.resolve("m2")).resolve("dep.jar");
        Files.write(library, new byte[0]);
        Path elsewhere = Files.createDirectories(tmp.resolve("scratch/classes"));
        EnvProbe.Workspace marks = EnvProbe.Workspace.of(ws);

        assertThat(marks.marks(from(Files.createDirectories(ws.resolve("app/target/classes")))))
                .as("a module's class tree")
                .isTrue();
        assertThat(marks.marks(from(sibling))).as("a sibling's jar").isTrue();
        assertThat(marks.marks(from(library))).as("a library's jar").isFalse();
        assertThat(marks.marks(from(elsewhere)))
                .as("a class tree outside the workspace, as a test compiles into its temp directory")
                .isTrue();
        assertThat(marks.marks(null)).isFalse();
    }

    @Test
    void the_workspace_reads_back_whole_from_the_argument() {
        String root = "/home/a b/w:s";
        EnvProbe.Uses uses = EnvProbe.Uses.of(EnvProbe.USES + root.length() + ":" + root + " !props /rec");
        assertThat(uses.workspace()).isEqualTo(root);
        assertThat(uses.rest()).isEqualTo("!props /rec");

        EnvProbe.Uses none = EnvProbe.Uses.of("/rec");
        assertThat(none.workspace()).isNull();
        assertThat(none.rest()).isEqualTo("/rec");
    }

    private static ProtectionDomain from(Path location) throws Exception {
        URL url = location.toUri().toURL();
        return new ProtectionDomain(new CodeSource(url, (Certificate[]) null), null);
    }
}
