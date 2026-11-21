// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class GraphCommandTest {

    /** The operation {@code name} of {@code jk code}, or of {@code jk graph} when code has none. */
    private static GraphOpCommand op(String name) {
        for (CliCommand c : new CodeCommand().subcommands()) {
            if (c.name().equals(name)) return (GraphOpCommand) c;
        }
        for (CliCommand c : new GraphCommand().subcommands()) {
            if (c.name().equals(name) && c instanceof GraphOpCommand g) return g;
        }
        throw new AssertionError("no operation " + name);
    }

    @Test
    void bareGraphBuildsAndTheGraphKeepsOnlyItsWalks() {
        assertThat(new GraphCommand().defaultSubcommand())
                .isNotNull()
                .extracting(CliCommand::name)
                .isEqualTo("build");
        assertThat(new GraphCommand().subcommands().stream().filter(c -> !c.hidden()))
                .extracting(CliCommand::name)
                .containsExactly("build", "path", "neighbors", "rules");
        assertThat(op("build").options()).isEmpty();
    }

    @Test
    void everyCodeQuestionAndEditIsACodeSubcommandSentAsItsOwnOperation() {
        assertThat(new CodeCommand().defaultSubcommand()).isNull();
        assertThat(new CodeCommand().subcommands())
                .extracting(CliCommand::name)
                .containsExactly("usages", "find", "read", "query", "rename", "delete", "move", "pull-up");
        for (String name : List.of("usages", "delete", "move", "pull-up")) {
            Invocation in = Invocation.builder()
                    .addPositional("Book.label")
                    .addPositional("Item")
                    .build();
            assertThat(op(name).request(Path.of("/w"), in, false).op()).isEqualTo(name);
        }
        Invocation delete = Invocation.builder()
                .addPositional("Book")
                .putValue("kind", "type")
                .build();
        assertThat(op("delete").request(Path.of("/w"), delete, false).options()).containsExactly("kind=type");
    }

    @Test
    void theReleasedGraphNamesAreHiddenAndSayTheNewOne() {
        Map<String, MovedCommand> moved = new LinkedHashMap<>();
        for (CliCommand c : new GraphCommand().subcommands()) {
            if (c instanceof MovedCommand m) moved.put(m.name(), m);
        }
        assertThat(moved).containsOnlyKeys("callers", "rename", "query", "read", "find");
        assertThat(moved.values()).allMatch(CliCommand::hidden);
        MovedCommand callers = Objects.requireNonNull(moved.get("callers"));
        assertThat(callers.notice()).isEqualTo("jk graph callers is now jk code usages");
        assertThat(Objects.requireNonNull(moved.get("read")).notice()).isEqualTo("jk graph read is now jk code read");
        assertThat(callers.options()).isEqualTo(op("usages").options());
        assertThat(callers.parameters()).isEqualTo(op("usages").parameters());
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
    void aLibraryQuestionNamesItsLibrary() {
        Invocation in = Invocation.builder()
                .addPositional("parse")
                .addPositional("json")
                .putValue("library", "jackson-databind")
                .build();
        GraphRequest r = op("query").request(Path.of("/w"), in, false);
        assertThat(r.args()).containsExactly("parse", "json");
        assertThat(r.options()).containsExactly("library=jackson-databind");
    }

    @Test
    void aNonNumberIsRefusedByName() {
        Invocation in = Invocation.builder().putValue("depth", "deep").build();
        assertThatThrownBy(() -> op("query").request(Path.of("/w"), in, false))
                .hasMessageContaining("--depth takes a number");
    }

    @Test
    void theBuildSummaryNamesWhatWasRead() {
        GraphAck ack =
                new GraphAck(null, "", 61_304, 167_942, 5_419, 0, 4_917, 460, "/w/target/graph", null, List.of());
        assertThat(GraphOpCommand.summary(ack))
                .contains("61,304 nodes · 167,942 edges from 5,419 files (none changed, 4,917 classes)");
    }

    @Test
    void noOptionsMeansTheDefaults() {
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
