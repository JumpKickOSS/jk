// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphCommandTest {

    private static GraphOpCommand op(String name) {
        for (CliCommand c : new GraphCommand().subcommands()) {
            if (c.name().equals(name)) return (GraphOpCommand) c;
        }
        throw new AssertionError("no graph " + name);
    }

    @Test
    void bareGraphBuildsAndEveryReadIsASubcommand() {
        assertThat(new GraphCommand().defaultSubcommand())
                .isNotNull()
                .extracting(CliCommand::name)
                .isEqualTo("build");
        assertThat(new GraphCommand().subcommands())
                .extracting(CliCommand::name)
                .containsExactly(
                        "build",
                        "query",
                        "explain",
                        "path",
                        "node",
                        "neighbors",
                        "callers",
                        "community",
                        "god-nodes",
                        "read",
                        "find",
                        "rules",
                        "stats",
                        "report");
    }

    @Test
    void optionsBecomeTheRequest() {
        Invocation in = Invocation.builder()
                .addPositional("order")
                .addPositional("total")
                .flag("dfs", true)
                .putValue("depth", "2")
                .putValue("budget", "500")
                .addValue("context", "call")
                .flag("no-refresh", true)
                .build();
        GraphRequest r = op("query").request(Path.of("/w"), in, true);
        assertThat(r.op()).isEqualTo("query");
        assertThat(r.args()).containsExactly("order", "total");
        assertThat(r.mode()).isEqualTo("dfs");
        assertThat(r.depth()).isEqualTo(2);
        assertThat(r.budget()).isEqualTo(500);
        assertThat(r.contexts()).containsExactly("call");
        assertThat(r.rebuild()).isTrue();
        assertThat(r.noRefresh()).isTrue();
    }

    @Test
    void scopeOptionsNarrowTheRead() {
        Invocation in = Invocation.builder()
                .addPositional("order")
                .putValue("scope", "server/**;!**/generated/**")
                .addValue("modules", "server/engine")
                .addValue("modules", "shared/*")
                .putValue("source-set", "main")
                .build();
        GraphRequest r = op("query").request(Path.of("/w"), in, false);
        assertThat(r.scope()).isEqualTo("server/**;!**/generated/**");
        assertThat(r.modules()).isEqualTo("server/engine,shared/*");
        assertThat(r.sourceSet()).isEqualTo("main");
        GraphRequest plain = op("query")
                .request(
                        Path.of("/w"),
                        Invocation.builder().addPositional("order").build(),
                        false);
        assertThat(plain.scope()).isNull();
        assertThat(plain.modules()).isNull();
        assertThat(plain.sourceSet()).isNull();
    }

    @Test
    void readAndFindSettingsTravelByName() {
        Invocation read = Invocation.builder()
                .addPositional("OrderService.*")
                .putValue("view", "structure")
                .build();
        assertThat(op("read").request(Path.of("/w"), read, false).options()).containsExactly("view=structure");
        Invocation find = Invocation.builder()
                .addPositional("*Service")
                .putValue("kind", "class,interface")
                .putValue("annotated", "Transactional")
                .build();
        GraphRequest r = op("find").request(Path.of("/w"), find, false);
        assertThat(r.args()).containsExactly("*Service");
        assertThat(r.options()).containsExactly("kind=class,interface", "annotated=Transactional");
    }

    @Test
    void aNonNumberIsRefusedByName() {
        Invocation in = Invocation.builder().putValue("depth", "deep").build();
        assertThatThrownBy(() -> op("query").request(Path.of("/w"), in, false))
                .hasMessageContaining("--depth takes a number");
    }

    @Test
    void theBuildSummaryNamesWhatWasRead() {
        GraphAck ack = new GraphAck(null, "", 61_304, 167_942, 1_771, 5_419, 0, 4_917, 460, "/w/target/graph", null);
        assertThat(GraphOpCommand.summary(ack))
                .contains(
                        "61,304 nodes · 167,942 edges · 1,771 communities from 5,419 files (none changed, 4,917 classes)");
    }

    @Test
    void noOptionsMeansGraphifysDefaults() {
        GraphRequest r = op("path")
                .request(
                        Path.of("/w"),
                        Invocation.builder()
                                .addPositional("A")
                                .addPositional("B")
                                .build(),
                        false);
        assertThat(r)
                .extracting(GraphRequest::depth, GraphRequest::budget, GraphRequest::maxHops, GraphRequest::undirected)
                .containsExactly(3, 2000, 8, false);
        assertThat(r.args()).isEqualTo(List.of("A", "B"));
    }
}
