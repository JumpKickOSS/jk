// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JavadocMode;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.ProjectInherit;
import org.junit.jupiter.api.Test;

/**
 * The {@code javadoc} key: absent → only for publish / {@code jk javadoc}, {@code true} → every
 * build, {@code false} → no jar, {@code "strict"} → every build with doclint on.
 */
class ManifestProjectJavadocTest {

    private static final String HEAD = "group = \"com.example\"\nname = \"lib\"\nversion = \"1.0.0\"\njava = 25\n";

    @Test
    void absent_documents_only_when_published_or_asked() {
        JavadocMode mode = JkBuildParser.parse(HEAD).project().javadocMode();
        assertThat(mode).isEqualTo(JavadocMode.PUBLISH);
        assertThat(mode.enabled()).as("a plain jk build writes no javadoc jar").isFalse();
        assertThat(mode.whenAsked()).as("jk publish / jk javadoc write it").isTrue();
    }

    @Test
    void true_documents_every_build() {
        JavadocMode mode =
                JkBuildParser.parse(HEAD + "javadoc = true\n").project().javadocMode();
        assertThat(mode).isEqualTo(JavadocMode.LENIENT);
        assertThat(mode.enabled()).isTrue();
        assertThat(JavadocMode.DISABLED.whenAsked())
                .as("false is never, even for publish")
                .isFalse();
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
