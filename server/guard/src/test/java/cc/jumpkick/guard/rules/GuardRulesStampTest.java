// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rule memos key on bytes, not on a millisecond mtime. */
class GuardRulesStampTest {

    @Test
    void a_same_size_edit_with_the_old_mtime_is_visible(@TempDir Path root) throws Exception {
        Path rules = root.resolve("jk-guards.toml");
        String first = "[guards]\nextends = [\"a:b:1\"]\n";
        String second = "[guards]\nextends = [\"c:d:1\"]\n";
        assertThat(first).hasSameSizeAs(second);
        Files.writeString(rules, first);
        FileTime mtime = Files.getLastModifiedTime(rules);
        String stamped = GuardRules.stamp(root);
        List<String> parsed = GuardPacks.declared(root);
        assertThat(parsed).containsExactly("a:b:1");

        Files.writeString(rules, second);
        Files.setLastModifiedTime(rules, mtime);
        assertThat(GuardPacks.declared(root)).containsExactly("c:d:1");
        assertThat(GuardPacks.declared(root)).containsExactly("c:d:1");
        assertThat(GuardRules.stamp(root)).isNotEqualTo(stamped);
        assertThat(GuardRules.stamp(root)).contains(Hashing.sha256Hex(second.getBytes(StandardCharsets.UTF_8)));
    }
}
