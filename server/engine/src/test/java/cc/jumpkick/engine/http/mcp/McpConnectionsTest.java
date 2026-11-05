// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.mcp.McpRevision;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The connection table as a cache over its store: past its bound it unloads the least recently
 * used idle connection, an unloaded one reloads as it was, and a new one is refused only when
 * every loaded connection is active.
 */
class McpConnectionsTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Map<String, Map<String, Object>> disk = new ConcurrentHashMap<>();
    private final McpConnections table = new McpConnections(now::get);

    {
        table.store(new McpConnections.Store() {
            @Override
            public void save(McpConnection connection) {
                disk.put(connection.id(), connection.toMap());
            }

            @Override
            public @Nullable McpConnection load(String id) {
                Map<String, Object> m = disk.get(id);
                return m == null ? null : McpConnection.of(m);
            }

            @Override
            public boolean holds(String id) {
                return disk.containsKey(id);
            }
        });
    }

    @Test
    void an_idle_connection_unloads_before_a_busy_one_that_was_used_less_recently() {
        table.max(2);
        McpConnection busy = opened("busy-agent");
        McpConnection idle = opened("idle-agent");
        busy.callStarted();
        quiet();

        McpConnection fresh = opened("new-agent");

        assertThat(table.loaded(busy.id())).isTrue();
        assertThat(table.loaded(idle.id())).isFalse();
        assertThat(table.loaded(fresh.id())).isTrue();
        assertThat(disk).as("the unloaded one stays stored").containsKey(idle.id());
    }

    @Test
    void an_unloaded_connection_returns_with_its_id_and_bind_and_is_never_refused() {
        table.max(1);
        McpConnection first = opened("claude-code");
        first.bind("/ws");
        quiet();
        McpConnection second = opened("cursor");
        assertThat(table.loaded(first.id())).isFalse();

        McpConnection back = requireNonNull(table.find(first.id()));

        assertThat(back.label()).isEqualTo(first.label());
        assertThat(back.dir()).isEqualTo("/ws");
        assertThat(table.loaded(second.id()))
                .as("the second was just used: the table runs over rather than refuse the first")
                .isTrue();
        assertThat(table.size()).isEqualTo(2);
    }

    @Test
    void counts_name_the_limit_the_loaded_connections_and_the_active_ones() {
        table.max(4);
        assertThat(table.counts()).isEqualTo(new McpConnections.Counts(4, 0, 0));
        McpConnection quietOne = opened("claude-code");
        McpConnection busy = opened("cursor");
        busy.callStarted();
        assertThat(table.counts()).isEqualTo(new McpConnections.Counts(4, 2, 2));

        quiet();
        assertThat(table.counts())
                .as("the quiet one is idle; the one with a call outstanding stays active")
                .isEqualTo(new McpConnections.Counts(4, 2, 1));
        assertThat(table.find(quietOne.id())).isNotNull();
        assertThat(table.counts()).as("a request makes it active again").isEqualTo(new McpConnections.Counts(4, 2, 2));
    }

    @Test
    void a_connection_with_a_call_outstanding_never_unloads_and_a_full_active_table_refuses_a_new_one() {
        table.max(1);
        McpConnection parked = opened("claude-code");
        parked.callStarted();
        now.addAndGet(Duration.ofHours(3).toMillis());

        assertThatThrownBy(() -> opened("cursor"))
                .isInstanceOfSatisfying(McpConnections.Full.class, full -> assertThat(full.retryAfterSeconds())
                        .isEqualTo(McpConnections.QUIET_MILLIS / 1000))
                .hasMessageContaining("all 1 sessions are active")
                .hasMessageContaining("[mcp] max-sessions")
                .hasMessageContaining("JK_MCP_MAX_SESSIONS");
        assertThat(table.find(parked.id()))
                .as("the existing connection still answers")
                .isSameAs(parked);

        parked.callEnded();
        assertThatThrownBy(() -> opened("cursor"))
                .as("just seen: still active until it has been quiet")
                .isInstanceOfSatisfying(McpConnections.Full.class, full -> assertThat(full.retryAfterSeconds())
                        .isEqualTo(McpConnections.QUIET_MILLIS / 1000));
        quiet();
        McpConnection fresh = opened("cursor");
        assertThat(table.loaded(fresh.id())).isTrue();
        assertThat(table.loaded(parked.id())).isFalse();
    }

    @Test
    void a_connection_the_store_does_not_hold_never_unloads() {
        table.max(1);
        McpConnection unsaved = table.open("claude-code", null, McpRevision.LATEST, McpElicitModes.NONE);
        quiet();
        assertThatThrownBy(() -> opened("cursor")).isInstanceOf(McpConnections.Full.class);
        assertThat(table.loaded(unsaved.id())).isTrue();
    }

    /** A connection opened and stored, as {@code initialize} leaves it. */
    private McpConnection opened(String client) {
        McpConnection c = table.open(client, null, McpRevision.LATEST, McpElicitModes.NONE);
        table.save(c);
        return c;
    }

    /** Long enough for a connection with nothing outstanding to count as idle. */
    private void quiet() {
        now.addAndGet(McpConnections.QUIET_MILLIS);
    }
}
