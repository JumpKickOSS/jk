// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.TestStamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forecast's run-tests stamp must key on the SESSION test selection, exactly like the live
 * run — with {@code TestSelection.DEFAULT} baked in, a widened {@code jk build -A} forecast-hit
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

        List<String> unit = SessionContext.where(Session.defaults(), () -> TestStampInputs.of(
                        TestStampInputs.Facts.of(dir, project), ClasspathFingerprint.ON_DISK)
                .extras());
        TestSelection widened = TestSelection.of(List.of(), true, List.of(), List.of(), true);
        List<String> all = SessionContext.where(Session.defaults().withTestSelection(widened), () -> TestStampInputs.of(
                        TestStampInputs.Facts.of(dir, project), ClasspathFingerprint.ON_DISK)
                .extras());

        String unitSel =
                unit.stream().filter(e -> e.startsWith("sel:")).findFirst().orElseThrow();
        String allSel =
                all.stream().filter(e -> e.startsWith("sel:")).findFirst().orElseThrow();
        assertThat(allSel).isNotEqualTo(unitSel);
        // And the default-session extras still fold the module's own [test] excludes.
        assertThat(unitSel).contains("integration");
    }

    /** A {@code --class} run's green marker must never be the one a whole-module run replays. */
    @Test
    void a_class_filtered_run_keys_its_stamp_apart_from_the_whole_module(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "t"
                version = "0.0.1"
                java = 25
                """);
        JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
        Path src = Files.writeString(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        Path lock = Files.writeString(dir.resolve("jk-lock.toml"), "v=1");

        List<String> whole = SessionContext.where(Session.defaults(), () -> TestStampInputs.of(
                        TestStampInputs.Facts.of(dir, project), ClasspathFingerprint.ON_DISK)
                .extras());
        TestSelection filtered = TestSelection.DEFAULT.withClasses(List.of("FooTest"));
        List<String> partial =
                SessionContext.where(Session.defaults().withTestSelection(filtered), () -> TestStampInputs.of(
                                TestStampInputs.Facts.of(dir, project), ClasspathFingerprint.ON_DISK)
                        .extras());

        String wholeKey = TestStamp.computeKey(List.of(src), mainClasses, List.of(), lock, List.of(), whole);
        String partialKey = TestStamp.computeKey(List.of(src), mainClasses, List.of(), lock, List.of(), partial);
        assertThat(wholeKey).isNotNull();
        assertThat(partialKey).isNotNull().isNotEqualTo(wholeKey);
    }
}
