// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.engine.sessions.SessionLog;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The decision queue's store beside the session records: written through the session log's write
 * gate, so only the engine that writes sessions writes decisions, and loaded once that log has
 * adopted, after the engine it displaced stopped writing.
 */
public final class JournaledDecisions implements DecisionQueue.Store {

    private final SessionLog log;

    public JournaledDecisions(SessionLog log) {
        this.log = Objects.requireNonNull(log);
    }

    @Override
    public boolean save(String id, Map<String, Object> record) {
        return log.whileWriting("decision " + id, journal -> journal.writeDecision(id, record));
    }

    @Override
    public void drop(String id) {
        log.whileWriting("decision " + id, journal -> journal.deleteDecision(id));
    }

    @Override
    public List<Map<String, Object>> load() {
        SessionJournal journal = log.adoptedJournal();
        return journal == null ? List.of() : journal.decisions();
    }
}
