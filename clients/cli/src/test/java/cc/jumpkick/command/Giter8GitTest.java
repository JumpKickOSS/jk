// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class Giter8GitTest {

    @Test
    void looks_remote_for_uris_and_github_shorthand() {
        assertThat(Giter8Git.looksRemote("https://github.com/x/y.git")).isTrue();
        assertThat(Giter8Git.looksRemote("git@github.com:x/y.git")).isTrue();
        assertThat(Giter8Git.looksRemote("owner/repo")).isTrue();
        assertThat(Giter8Git.looksRemote("owner/repo.g8")).isTrue();
        assertThat(Giter8Git.looksRemote("owner/repo#main")).isTrue();
        assertThat(Giter8Git.looksRemote("java-cli")).isFalse();
        assertThat(Giter8Git.looksRemote("./local.g8")).isFalse();
    }

    @Test
    void parse_github_shorthand_to_https() throws Exception {
        var p = Giter8Git.parse("jkbuild/java-cli.g8#main");
        assertThat(p.url()).isEqualTo("https://github.com/jkbuild/java-cli.git");
        assertThat(p.rev()).isEqualTo("main");
        assertThat(p.cacheKey()).contains("github.com_jkbuild_java-cli");
    }

    @Test
    void parse_rejects_garbage() {
        assertThatThrownBy(() -> Giter8Git.parse("not a ref"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a git template ref");
    }
}
