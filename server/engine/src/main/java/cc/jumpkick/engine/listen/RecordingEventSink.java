// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import java.util.ArrayList;
import java.util.List;

/** Test sink. */
public final class RecordingEventSink implements EventSink {
    private final List<EngineEvent> events = new ArrayList<>();

    @Override
    public void emit(EngineEvent event) {
        events.add(event);
    }

    public List<EngineEvent> events() {
        return List.copyOf(events);
    }
}
