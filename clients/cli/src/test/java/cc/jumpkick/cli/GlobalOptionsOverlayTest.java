// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.command.Command;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The engine reads rebuild/offline off the session wire, so {@code GlobalOptions.from}
 * must fold them into the session overlay — {@code Jk.applyCliOverrides} catches only exact
 * tokens, and a bundled {@code -rq} or abbreviated {@code --red}/{@code --offl} would otherwise
 * parse fine client-side while the engine serves a fully cached build.
 */
class GlobalOptionsOverlayTest {

    private static final Command DEMO = new Command() {
        @Override
        public String name() {
            return "demo";
        }

        @Override
        public String description() {
            return "demo";
        }

        @Override
        public List<Opt> options() {
            return GlobalOptions.globalOpts();
        }
    };

    private static Invocation parse(String... args) throws Exception {
        return ArgParser.parse(DEMO, List.of(args));
    }

    @Test
    void bundled_and_abbreviated_redo_reach_the_session_overlay() throws Exception {
        Session original = SessionContext.installed();
        try {
            GlobalOptions bundled = GlobalOptions.from(parse("-rq"));
            assertThat(bundled.rebuild).isTrue();
            assertThat(SessionContext.current().config().rebuildOr(false))
                    .as("engine wire reads rebuild from the session")
                    .isTrue();

            SessionContext.install(original);
            GlobalOptions abbreviated = GlobalOptions.from(parse("--red"));
            assertThat(abbreviated.rebuild).isTrue();
            assertThat(SessionContext.current().config().rebuildOr(false)).isTrue();
        } finally {
            SessionContext.install(original);
        }
    }

    @Test
    void abbreviated_offline_reaches_the_session_overlay() throws Exception {
        Session original = SessionContext.installed();
        try {
            GlobalOptions g = GlobalOptions.from(parse("--offl"));
            assertThat(g.offline).isTrue();
            assertThat(SessionContext.current().config().offlineOr(false)).isTrue();
        } finally {
            SessionContext.install(original);
        }
    }
}
