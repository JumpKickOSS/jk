// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JavadocMode;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.ProjectInherit;
import org.junit.jupiter.api.Test;

/** The {@code javadoc} key: absent → lenient, {@code false} → no jar, {@code "strict"} → doclint on. */
class ManifestProjectJavadocTest {

    private static final String HEAD = "group = \"com.example\"\nname = \"lib\"\nversion = \"1.0.0\"\njava = 25\n";

    @Test
    void absent_is_lenient() {
        assertThat(JkBuildParser.parse(HEAD).project().javadocMode()).isEqualTo(JavadocMode.LENIENT);
    }

    @Test
    void false_disables_the_javadoc_jar() {
        assertThat(JkBuildParser.parse(HEAD + "javadoc = false\n").project().javadocMode())
                .isEqualTo(JavadocMode.DISABLED);
        assertThat(JkBuildParser.parse(HEAD + "javadoc = \"off\"\n").project().javadocMode())
                .isEqualTo(JavadocMode.DISABLED);
    }

    @Test
    void strict_keeps_doclint_on() {
        assertThat(JkBuildParser.parse(HEAD + "javadoc = \"strict\"\n")
                        .project()
                        .javadocMode())
                .isEqualTo(JavadocMode.STRICT);
        assertThat(JkBuildParser.parse(HEAD + "javadoc = true\n").project().javadocMode())
                .isEqualTo(JavadocMode.LENIENT);
    }

    @Test
    void a_member_inherits_the_workspace_roots_setting() {
        JkBuild root = JkBuildParser.parse(HEAD + "javadoc = false\n\n[workspace]\nmodules = [\"m\"]\n");
        Project member = JkBuildParser.parse("name = \"m\"\n").project();
        assertThat(member.inherits(ProjectInherit.JAVADOC)).isTrue();
        assertThat(member.resolveFromWorkspaceRoot(root.project()).javadocMode())
                .isEqualTo(JavadocMode.DISABLED);
        Project own =
                JkBuildParser.parse("name = \"m\"\njavadoc = \"strict\"\n").project();
        assertThat(own.inherits(ProjectInherit.JAVADOC)).isFalse();
        assertThat(own.resolveFromWorkspaceRoot(root.project()).javadocMode()).isEqualTo(JavadocMode.STRICT);
    }
}
