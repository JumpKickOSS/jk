// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.KeyContract;
import cc.jumpkick.test.ForkSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run-tests stamp keys what each launch starts its test JVMs with by value: the fork spec {@link
 * TestLaunch#forkSpec} derives, the one the launch itself runs from. The code that forks the JVMs is
 * in no code key, so an edit to it that leaves the spec as it was moves no stamp; a flag or variable
 * the spec spells moves the stamp of exactly the suites whose launch spells it.
 */
class TestStampForkSpecTest {

    private static final String PLAIN = "";

    private static final String NO_ASSERTIONS = """
            [test]
            assertions = false
            """;

    private static final String TIERED = """
            [test.suites.slow]
            tags = ["slow"]
            """;

    @AfterEach
    void forget() {
        KeyContract.SUITE.overrideDigestForTests(null);
    }

    @Test
    void the_stamp_keys_the_spec_the_launch_runs_from(@TempDir Path tmp) throws Exception {
        JkBuild project = module(tmp.resolve("m"), PLAIN);

        List<String> forks = forkTokens(extras(tmp.resolve("m"), project));

        assertThat(forks)
                .containsExactly("fork:"
                        + TestLaunch.forkSpec(project, TestSelection.DEFAULT, Set.of("test"))
                                .token());
    }

    /**
     * The spec spells every flag a launch adds around the module's own and every variable it gives
     * the JVMs by default, so a change to how one is spelled moves the stamp of each suite whose
     * launch adds it, and of no other.
     */
    @Test
    void a_flag_the_spec_spells_moves_exactly_the_suites_that_spell_it(@TempDir Path tmp) throws Exception {
        String plain = forkToken(tmp.resolve("plain"), PLAIN);
        String noAssertions = forkToken(tmp.resolve("no-assertions"), NO_ASSERTIONS);
        String tiered = forkToken(tmp.resolve("tiered"), TIERED);

        assertThat(List.of(plain, tiered)).allMatch(token -> token.startsWith("fork:-ea "));
        assertThat(noAssertions).doesNotContain("-ea");
        assertThat(tiered).contains("env:" + Http.DENY_HOSTS_ENV + "=" + Http.ANY_REMOTE_HOST);
        assertThat(List.of(plain, noAssertions)).noneMatch(token -> token.contains(Http.DENY_HOSTS_ENV));
        for (String flag : ForkSpec.DEFAULT.trailing()) {
            assertThat(List.of(plain, noAssertions, tiered)).allMatch(token -> token.contains(flag));
        }
    }

    /** A setting the spec carries reaches the stamp through the spec alone. */
    @Test
    void a_setting_the_spec_carries_moves_the_fork_token_and_nothing_else(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("m");
        List<String> on = extras(dir, module(dir, PLAIN));
        List<String> off = extras(dir, module(dir, NO_ASSERTIONS));

        List<String> onlyOn = new ArrayList<>(on);
        onlyOn.removeAll(off);
        List<String> onlyOff = new ArrayList<>(off);
        onlyOff.removeAll(on);
        assertThat(onlyOn).hasSize(1).allMatch(token -> token.startsWith("fork:"));
        assertThat(onlyOff).hasSize(1).allMatch(token -> token.startsWith("fork:"));
    }

    /** The fork token names no code: the suite code's digest moving leaves it as it was. */
    @Test
    void the_fork_token_names_no_code(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("m");
        JkBuild project = module(dir, PLAIN);
        KeyContract.SUITE.overrideDigestForTests("suites000001");
        List<String> one = forkTokens(extras(dir, project));
        KeyContract.SUITE.overrideDigestForTests("suites000002");

        assertThat(forkTokens(extras(dir, project))).isEqualTo(one);
    }

    private static String forkToken(Path dir, String test) throws Exception {
        List<String> forks = forkTokens(extras(dir, module(dir, test)));
        assertThat(forks).hasSize(1);
        return forks.getFirst();
    }

    private static List<String> forkTokens(List<String> extras) {
        return extras.stream().filter(token -> token.startsWith("fork:")).toList();
    }

    private static JkBuild module(Path dir, String test) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """ + test);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    private static List<String> extras(Path dir, JkBuild project) throws Exception {
        return TestStampInputs.of(TestStampInputs.Facts.of(dir, project), ClasspathFingerprint.ON_DISK)
                .extras();
    }
}
