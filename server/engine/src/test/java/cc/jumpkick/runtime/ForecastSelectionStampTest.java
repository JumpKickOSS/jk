// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forecast's run-tests stamp must key on the SESSION test selection, exactly like the live
 * run — with {@code TestSelection.DEFAULT} baked in, a widened {@code jk build --all} forecast-hit
 * the unit-tier green marker and the whole workspace short-circuited to "up to date" without
 * running a single widened test.
 */
class ForecastSelectionStampTest {

    @Test
    void widened_session_changes_the_forecast_stamp_extras(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "t"
                version = "0.0.1"
                java = 25

                [test]
                exclude-tags = ["integration", "slow"]
                """);
        JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));

        List<String> unit =
                SessionContext.where(Session.defaults(), () -> PlannerSupport.testStampExtras(dir, project));
        TestSelection widened = TestSelection.of(List.of(), true, List.of(), List.of(), true);
        List<String> all = SessionContext.where(
                Session.defaults().withTestSelection(widened), () -> PlannerSupport.testStampExtras(dir, project));

        String unitSel =
                unit.stream().filter(e -> e.startsWith("sel:")).findFirst().orElseThrow();
        String allSel =
                all.stream().filter(e -> e.startsWith("sel:")).findFirst().orElseThrow();
        assertThat(allSel).isNotEqualTo(unitSel);
        // And the default-session extras still fold the module's own [test] excludes.
        assertThat(unitSel).contains("integration");
    }
}
