// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import java.util.List;

/** Fan-out. Replaces "listener that also publishEvent". */
public final class CompositeEventSink implements EventSink {
    private final List<EventSink> sinks;

    public CompositeEventSink(EventSink... sinks) {
        this.sinks = List.of(sinks);
    }

    @Override
    public void emit(EngineEvent event) {
        for (EventSink s : sinks) s.emit(event);
    }

    @Override
    public void close() {
        for (EventSink s : sinks) s.close();
    }
}
