// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.runtime.workspace.BuildService;

/**
 * Whether a journalled run was up to date: a build of the tree (a test, guard or install run
 * included) that succeeded and ran no step that did work ({@link BuildService#didWork}), a run that
 * entered no module included. The record's form of {@code WorkspaceResult.upToDate}, read by the
 * agent report's headline.
 */
final class RecordUpToDate {

    private RecordUpToDate() {}

    static boolean of(BuildRecord r) {
        if (!r.success() || r.cancelled() || r.running() || !BuildAccumulator.NOOP_KINDS.contains(r.kind())) {
            return false;
        }
        for (BuildRecord.Task t : r.steps()) {
            if (BuildService.didWork(t.name(), t.status())) return false;
        }
        for (BuildRecord.Module m : r.modules()) {
            if (!m.success()) return false;
            for (BuildRecord.Task t : m.steps()) {
                if (BuildService.didWork(t.name(), t.status())) return false;
            }
        }
        return true;
    }
}
