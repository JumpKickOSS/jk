// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.wire.protocol.EventCatalog;
import org.junit.jupiter.api.Test;

/**
 * MCP's column of {@link EventCatalog} follows SSE's, as the hub delivers it: every SSE frame
 * reaches an MCP subscriber as {@code notifications/jk/event}, but for the dashboard's own frames,
 * which are published to dashboard subscribers alone.
 */
class EventCatalogMcpTest {

    @Test
    void every_frame_sse_carries_reaches_mcp_but_the_dashboards_own() throws Exception {
        for (EventCatalog.Row row : EventCatalog.rows()) {
            if (!(row.sse() instanceof EventCatalog.Carried carried)) continue;
            HttpEvents hub = new HttpEvents();
            try (HttpEvents.Subscription mcp = hub.subscribe(HttpEvents.FrameStyle.MCP)) {
                JsonOut payload = JsonOut.object().put("jid", 1);
                if (EventCatalog.DASHBOARD_ONLY.contains(row.type())) {
                    hub.publishDashboard(carried.as(), payload);
                } else {
                    hub.publish(carried.as(), payload);
                }
                String frame = mcp.next(200);
                boolean expected = EventCatalog.carries(EventCatalog.Surface.MCP, row.type());
                assertThat(frame != null)
                        .as(
                                "%s (SSE `%s`) on MCP: the catalog says %s",
                                row.type(), carried.as(), expected ? "carried" : "omitted")
                        .isEqualTo(expected);
                if (frame != null) {
                    assertThat(frame).contains("notifications/jk/event").contains(carried.as());
                }
            }
        }
    }
}
