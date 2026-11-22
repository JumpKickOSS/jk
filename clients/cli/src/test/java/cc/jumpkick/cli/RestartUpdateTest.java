// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.config.SelfUpdatePolicy.Action;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RestartUpdateTest {

    private static final String RUNNING = "0.16.2";

    private final FakeHome home = new FakeHome();

    private Optional<String> due(@Nullable String command) {
        return RestartUpdate.due(RUNNING, command, home);
    }

    @Test
    void a_download_installs_before_the_next_engine_starts() {
        assertThat(due("build")).contains("0.17.0");
        assertThat(due("test")).contains("0.17.0");
    }

    @Test
    void nothing_installs_while_an_engine_runs_or_a_command_holds_the_install() {
        home.quiet = false;
        assertThat(due("build")).isEmpty();
    }

    @Test
    void ci_never_replaces_its_own_jk() {
        home.ci = true;
        assertThat(due("build")).isEmpty();
    }

    @Test
    void updating_off_installs_nothing() {
        home.enabled = false;
        assertThat(due("build")).isEmpty();
        home.enabled = true;
        home.action = Action.OFF;
        assertThat(due("build")).isEmpty();
        home.action = Action.ASK;
        assertThat(due("build")).isEmpty();
    }

    @Test
    void a_download_that_is_not_newer_or_lost_its_blobs_installs_nothing() {
        home.prepared = prepared("0.16.2");
        assertThat(due("build")).isEmpty();
        home.prepared = prepared("0.17.0");
        home.intact = false;
        assertThat(due("build")).isEmpty();
        home.intact = true;
        home.prepared = null;
        assertThat(due("build")).isEmpty();
    }

    @Test
    void a_newer_install_is_handed_the_command_as_it_is() {
        home.installed = "0.17.0";
        assertThat(due("build")).isEmpty();
    }

    @Test
    void development_clients_and_spawnless_runs_never_install() {
        assertThat(RestartUpdate.due("0.17.0-SNAPSHOT", "build", home)).isEmpty();
        home.released = false;
        assertThat(due("build")).isEmpty();
        home.released = true;
        home.spawnAllowed = false;
        assertThat(due("build")).isEmpty();
    }

    @Test
    void management_commands_hooks_and_protocol_servers_are_left_alone() {
        assertThat(due(null)).isEmpty();
        for (String command : RestartUpdate.SKIPPED_COMMANDS) {
            assertThat(due(command)).as(command).isEmpty();
        }
    }

    private static PreparedUpdate prepared(String version) {
        return new PreparedUpdate(
                version, RUNNING, "a".repeat(64), PreparedUpdate.Client.NATIVE, "b".repeat(64), Instant.EPOCH);
    }

    private static final class FakeHome implements RestartUpdate.Home {
        boolean released = true;
        boolean enabled = true;
        boolean ci;
        boolean spawnAllowed = true;
        Action action = Action.AUTO;

        @Nullable
        PreparedUpdate prepared = RestartUpdateTest.prepared("0.17.0");

        String installed = RUNNING;
        boolean quiet = true;
        boolean intact = true;

        @Override
        public boolean releasedClient() {
            return released;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public boolean ci() {
            return ci;
        }

        @Override
        public boolean spawnAllowed() {
            return spawnAllowed;
        }

        @Override
        public Action action() {
            return action;
        }

        @Override
        public Optional<PreparedUpdate> prepared() {
            return Optional.ofNullable(prepared);
        }

        @Override
        public Optional<String> installed() {
            return Optional.of(installed);
        }

        @Override
        public boolean quiet() {
            return quiet;
        }

        @Override
        public boolean intact(PreparedUpdate p) {
            return intact;
        }
    }
}
