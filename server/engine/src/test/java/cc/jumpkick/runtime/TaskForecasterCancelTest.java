// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A cancelled job's forecast stops at the next module rather than pricing the rest of the
 * workspace: the walk is per module, and a thousand-module reactor otherwise keeps an abandoned
 * runner busy for minutes after the client has gone.
 */
class TaskForecasterCancelTest {

    @Test
    void a_cancelled_session_ends_the_forecast_at_the_module_boundary(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["a", "b"]
                """);
        for (String m : new String[] {"a", "b"}) {
            Path dir = Files.createDirectories(tmp.resolve(m));
            Files.writeString(dir.resolve("jk.toml"), """
                    group = "com.ex"
                    name = "%s"
                    version = "0.1.0"
                    java = 25
                    """.formatted(m));
        }
        JkBuild root = JkBuildParser.parse(tmp.resolve("jk.toml"));
        BuildGraph.Result graph = BuildGraph.resolve(tmp, root);
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Cas cas = new Cas(cache.resolve("cas"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));

        Session.CancelToken token = Session.CancelToken.live();
        token.cancel();
        Session cancelled = Session.defaults().withCancel(token).withCacheDir(cache);
        assertThatThrownBy(() -> SessionContext.where(cancelled, () -> TaskForecaster.of(graph, cas, ac, cache)))
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("forecast cancelled after 0 of 2 modules");
    }
}
