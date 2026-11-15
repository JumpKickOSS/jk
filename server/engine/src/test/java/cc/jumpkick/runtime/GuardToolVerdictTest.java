// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Os;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A guard lane whose guards skipped for want of a tool keeps its verdict while the tool is missing,
 * and installing the tool is a miss.
 */
class GuardToolVerdictTest {

    @Test
    void a_verdict_on_a_missing_tool_holds_until_the_tool_is_installed(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(Os.isWindows(), "the stand-in tool is a shell script");
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        String tool = "jk-lint-stand-in";
        String laneKey = "lane-key";

        // What the lane stores for a run whose only skip named the tool.
        cache.storeVerdict("guard", laneKey, Map.of(GuardVerdicts.TOOLS, tool));
        cache.storeVerdict("guard", GuardVerdicts.toolKey(laneKey, List.of(tool), tmp, bin.toString()), Map.of());

        assertThat(GuardVerdicts.lookup(cache, laneKey, tmp, bin.toString()))
                .as("the tool is still missing: the verdict holds")
                .isPresent();

        Path installed = bin.resolve(tool);
        Files.writeString(installed, "#!/bin/sh\necho 'jk-lint-stand-in 1.0'\n");
        Files.setPosixFilePermissions(installed, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(GuardVerdicts.lookup(cache, laneKey, tmp, bin.toString()))
                .as("installing the tool has the guard judged")
                .isEmpty();
    }

    @Test
    void a_verdict_naming_no_tool_is_the_lane_key_itself(@TempDir Path tmp) throws Exception {
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
        cache.storeVerdict("guard", "lane-key", Map.of());
        assertThat(GuardVerdicts.lookup(cache, "lane-key", tmp, "")).isPresent();
        assertThat(GuardVerdicts.lookup(cache, "other-key", tmp, "")).isEmpty();
    }
}
