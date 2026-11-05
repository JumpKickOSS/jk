// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.SseEvents;
import cc.jumpkick.engine.sessions.EpisodeInference;
import cc.jumpkick.engine.sessions.Fact;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.EventCatalog;
import cc.jumpkick.wire.protocol.FailureTextRefs;
import cc.jumpkick.wire.protocol.PlanFinishEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The SSE event sink publishes exactly what {@link EventCatalog} says it does: every engine event
 * is pushed through the real sink, and a frame comes out for it when, and only when, its row says
 * SSE carries it from the event sink. Every event has a row, and every row the sink is said to
 * publish is published by some event — a stale row fails as surely as an empty case did. The
 * session log's rows are published by the session log, and by it alone.
 */
class EventCatalogSseTest {

    @Test
    void the_sink_publishes_exactly_what_the_catalog_says() throws Exception {
        Set<String> publishedTypes = new LinkedHashSet<>();
        for (Class<?> variant : EngineEvent.class.getPermittedSubclasses()) {
            EngineEvent event = (EngineEvent) populated(variant);
            EventCatalog.Row row = Objects.requireNonNull(
                    EventCatalog.row(event.type()), variant.getSimpleName() + "'s type has a row in EventCatalog");
            assertThat(row)
                    .as("%s's type `%s` has a row in EventCatalog", variant.getSimpleName(), event.type())
                    .isNotNull();

            List<String> calls = new ArrayList<>();
            List<String> records = new ArrayList<>();
            new SseEventSink(recording(calls, records), 7L).emit(event);

            boolean expected = row.sse() instanceof EventCatalog.Carried carried
                    && carried.fromEventSink()
                    && !(event instanceof EngineEvent.PlanFinishLine);
            assertThat(!calls.isEmpty())
                    .as(
                            "%s (%s): the catalog says the sink %s it; it called %s",
                            variant.getSimpleName(), event.type(), expected ? "publishes" : "omits", calls)
                    .isEqualTo(expected);
            if (!calls.isEmpty()) publishedTypes.add(event.type());
            if (!records.isEmpty()) {
                String sent = event instanceof EngineEvent.PlanFinish f
                        ? new PlanFinishEvent(f.dir(), f.success(), false).encode()
                        : WireEventSink.encode(event, new FailureTextRefs());
                assertThat(records)
                        .as("%s reaches SSE as the record the wire sends", variant.getSimpleName())
                        .containsExactly(sent);
            }
        }
        for (EventCatalog.Row row : EventCatalog.rows()) {
            if (row.sse() instanceof EventCatalog.Carried carried && carried.fromEventSink()) {
                assertThat(publishedTypes)
                        .as("EventCatalog says the SSE event sink publishes `%s`; no engine event did", row.type())
                        .contains(row.type());
            }
        }
    }

    /**
     * The session log publishes exactly the session rows of the catalog, each under the name its SSE
     * column carries and with a payload whose {@code type} is that name; a session, its episode
     * starting, retitled and settled, an action, idle and ended are all driven through it.
     */
    @Test
    void the_session_log_publishes_exactly_the_session_rows() {
        Set<String> rows = Set.of(EventCatalog.SSE_SESSION, EventCatalog.SSE_EPISODE, EventCatalog.SSE_ACTION);
        for (String type : rows) {
            EventCatalog.Row row = Objects.requireNonNull(EventCatalog.row(type), type + " has a row");
            assertThat(row.sse())
                    .as("`%s` on SSE", type)
                    .isEqualTo(EventCatalog.Carried.as(type, ((EventCatalog.Carried) row.sse()).how()));
            assertThat(row.wire()).as("`%s` on the wire", type).isInstanceOf(EventCatalog.Omitted.class);
            assertThat(row.consoleJsonl()).as("`%s` on -O jsonl", type).isInstanceOf(EventCatalog.Omitted.class);
            assertThat(EventCatalog.carries(EventCatalog.Surface.MCP, type))
                    .as("`%s` on MCP", type)
                    .isFalse();
        }

        long[] now = {1_700_000_000_000L};
        Set<String> published = new LinkedHashSet<>();
        SessionLog log = new SessionLog(null, () -> now[0], EpisodeInference.DEFAULT_IDLE_GAP_MILLIS);
        log.events((type, payload) -> {
            assertThat(payload.get("type")).as("the payload of `%s`", type).isEqualTo(type);
            published.add(type);
        });
        log.open("mcp", "claude-code", "claude-code 3f9a", "/ws/app");
        log.action(
                "mcp",
                "claude-code 3f9a",
                "claude-code",
                Fact.action(now[0], 1, "/ws/app", Fact.Kind.RENAME, true, "PricingRules.weekendUplift", 0),
                "rename",
                "rename PricingRules.weekendUplift → uplift",
                null);
        now[0] += EpisodeInference.DEFAULT_IDLE_GAP_MILLIS + 1;
        log.sweep();
        log.end("mcp", "claude-code 3f9a");

        assertThat(published).as("what the session log published").isEqualTo(rows);
    }

    /** Every engine event the wire sink encodes is sent as the type it says it is. */
    @Test
    void each_engine_event_is_sent_as_its_type() throws Exception {
        for (Class<?> variant : EngineEvent.class.getPermittedSubclasses()) {
            EngineEvent event = (EngineEvent) populated(variant);
            // buildplan-finish reaches the wire as the verb's pre-encoded PlanFinishLine; PlanFinish
            // is its SSE twin and sends nothing itself.
            if (event instanceof EngineEvent.PlanFinishLine || event instanceof EngineEvent.PlanFinish) continue;
            String line = WireEventSink.encode(event, new FailureTextRefs());
            if (line == null) {
                assertThat(EventCatalog.carries(EventCatalog.Surface.WIRE, event.type()))
                        .as("%s sends nothing on the wire; its row must say so", variant.getSimpleName())
                        .isFalse();
                continue;
            }
            assertThat(EngineProtocol.typeOf(line)).as(variant.getSimpleName()).isEqualTo(event.type());
        }
    }

    /** An {@link SseEvents} that records the name of every method called on it, and the records it was handed. */
    private static SseEvents recording(List<String> calls, List<String> records) {
        return (SseEvents) Proxy.newProxyInstance(
                SseEvents.class.getClassLoader(), new Class<?>[] {SseEvents.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return method.invoke(calls, args);
                    if (method.getName().equals("eventsWanted")) return true;
                    calls.add(method.getName());
                    if (!method.getName().equals("publishPlan")) records.add((String) args[args.length - 1]);
                    return null;
                });
    }

    /** A record with every component set: strings, numbers and nested records filled in. */
    private static Object populated(Class<?> type) throws ReflectiveOperationException {
        if (type.isRecord()) {
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = value(types[i]);
            }
            Constructor<?> ctor = type.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(values);
        }
        throw new IllegalArgumentException("cannot populate " + type);
    }

    private static Object value(Class<?> type) throws ReflectiveOperationException {
        if (type == String.class) return "x";
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 1;
        if (type == long.class || type == Long.class) return 1L;
        if (type == double.class || type == Double.class) return 1.0;
        if (type == List.class) return List.of();
        if (type == Set.class) return Set.of();
        if (type == Map.class) return Map.of();
        if (type == Optional.class) return Optional.empty();
        if (type == Path.class) return Path.of("/x");
        if (type.isEnum()) return type.getEnumConstants()[0];
        if (type.isRecord()) return populated(type);
        throw new IllegalArgumentException("no value for " + type);
    }
}
