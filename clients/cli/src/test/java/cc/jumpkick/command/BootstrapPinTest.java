// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.version.Versions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The release {@code .jk/ci-bootstrap-version} pins is the only jk that can build this tree from
 * nothing, so the tree must stay within what that release reads: the lock's schema is the frozen
 * one, its jk-min floor does not exceed the pin, and the pin itself is one released version no
 * newer than the tree. A format change that breaks this ships reader first, writer second
 * (docs/contributors/self-host.md, "The bootstrap chain"); this test is where the branch that
 * forgot finds out before the self-host job does.
 */
class BootstrapPinTest {

    private static final Path ROOT = RepoRoot.find(BootstrapPinTest.class);

    @Test
    void the_pinned_release_can_read_the_tree_s_lock() throws Exception {
        String pin = Files.readString(ROOT.resolve(".jk/ci-bootstrap-version")).trim();
        assertThat(pin).as("one released x.y.z, nothing else").matches("\\d+\\.\\d+\\.\\d+");
        assertThat(Versions.compare(pin, JkVersion.VERSION))
                .as("a bootstrap release is cut from a tree, so it is never ahead of one")
                .isLessThanOrEqualTo(0);

        Path lockFile = ROOT.resolve("jk-lock.toml");
        Matcher schema = Pattern.compile("(?m)^version\\s*=\\s*(\\d+)\\s*$").matcher(Files.readString(lockFile));
        assertThat(schema.find()).as("the lock states its schema version").isTrue();
        assertThat(Integer.parseInt(schema.group(1)))
                .as("the lock schema stays at the frozen version every hosted release reads")
                .isEqualTo(Lockfile.CURRENT_VERSION)
                .isEqualTo(1);
        Lockfile lock = LockfileReader.read(lockFile);
        String floor = lock.jkMin();
        if (floor != null && !floor.isBlank()) {
            assertThat(Versions.compare(floor, pin))
                    .as(
                            "jk-min %s must not exceed the bootstrap pin %s: the pinned release must be allowed to build the tree",
                            floor, pin)
                    .isLessThanOrEqualTo(0);
        }
    }
}
