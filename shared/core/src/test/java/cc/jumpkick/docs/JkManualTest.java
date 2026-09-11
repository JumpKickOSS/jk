// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkManualTest {

    @Test
    void markdown_is_a_self_contained_playbook() {
        String md = JkManual.markdown();
        assertThat(md).contains("JumpKick " + JkVersion.VERSION);
        assertThat(md).doesNotContain("${jk.version}");
        assertThat(md).doesNotContain("\r");
        assertThat(md).endsWith("\n");
        assertThat(md).contains("# JumpKick playbook");
        assertThat(md).contains("jk.toml");
        assertThat(md).contains("jk-lock.toml");
        assertThat(md).contains("target/jk-results.md");
        assertThat(md).contains("jk_manual");
        assertThat(md).contains("jk://manual");
        assertThat(md).contains("jk format");
        assertThat(md).contains("jkx");
        assertThat(md).contains("java = N");
        assertThat(md).contains("Do not invent `pom.xml`");
        assertThat(md).contains("unit suite only");
        assertThat(md).contains("do **not** run `--all` as a habit");
        assertThat(md).contains("jk test --guard");
        assertThat(md).contains("raw.githubusercontent.com/JumpKickOSS/jk");
        assertThat(md).contains("jumpkick.build/documentation");
        assertThat(md).contains("`security`");
        assertThat(md).contains("## Guards (house rules)");
        assertThat(md).contains("A guard failure's `code` is a rule id");
        assertThat(md).contains("never edit the baseline, never add a comment");
        assertThat(md).contains("jk guard explain <id>");
        String guards = md.substring(md.indexOf("## Guards (house rules)"), md.indexOf("## More documentation"));
        assertThat(guards.length() / 4)
                .as("the Guards page is read every session: ~600 tokens, not a reference manual")
                .isLessThan(700);
    }

    @Test
    void agents_guide_writes_once(@TempDir Path dir) throws Exception {
        assertThat(JkManual.ensureAgentsGuide(dir)).isTrue();
        Path file = dir.resolve("AGENTS.md");
        assertThat(file).exists();
        String body = Files.readString(file);
        assertThat(body).isEqualTo(JkManual.AGENTS_MD);
        assertThat(body).contains("jk manual");
        assertThat(body).contains("target/jk-results.md");
        assertThat(body).contains("unit");
        assertThat(body).contains("--all");
        assertThat(body).contains("jk guard explain <id>");
        assertThat(body).contains("jk-guards-baseline.toml");
        assertThat(body).contains("mcpUrl");
        assertThat(body).contains("`130` interrupted");
        assertThat(body).contains("jk web --no-open");
        assertThat(body.length() / 4)
                .as("the agents guide is read every session: a bootstrap, not a manual")
                .isLessThan(1000);

        Files.writeString(file, "# custom\n");
        assertThat(JkManual.ensureAgentsGuide(dir)).isFalse();
        assertThat(Files.readString(file)).isEqualTo("# custom\n");
    }
}
