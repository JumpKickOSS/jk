// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who holds the worker budget, as a wait line names them: {@code ; held by job #3 6.1 GiB in 9
 * forks, build-script host 608 MiB (asked to exit), 2 more}, largest first.
 */
final class LeaseHolders {

    /** The holder named for leases of other engines of this host. */
    static final String OTHER_ENGINES = "other engines";

    private final Map<String, Holder> byOwner = new LinkedHashMap<>();

    /** One held lease of {@code owner}, charged {@code bytes}; {@code asked} when it was asked to exit. */
    void add(String owner, long bytes, boolean asked) {
        Holder h = byOwner.computeIfAbsent(owner, Holder::new);
        h.bytes += bytes;
        h.forks++;
        h.asked |= asked;
    }

    /**
     * The line, with {@code otherEngines} bytes held by other engines, naming at most {@code shown}
     * holders; {@code ""} when none holds any.
     */
    String line(long otherEngines, int shown) {
        if (otherEngines > 0) byOwner.computeIfAbsent(OTHER_ENGINES, Holder::new).bytes += otherEngines;
        if (byOwner.isEmpty()) return "";
        List<Holder> ranked = new ArrayList<>(byOwner.values());
        ranked.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        StringBuilder line = new StringBuilder("; held by ");
        int named = Math.min(shown, ranked.size());
        for (int i = 0; i < named; i++) {
            if (i > 0) line.append(", ");
            Holder h = ranked.get(i);
            line.append(h.owner).append(' ').append(WorkerLeases.format(h.bytes));
            if (h.forks > 1) line.append(" in ").append(h.forks).append(" forks");
            if (h.asked) line.append(" (asked to exit)");
        }
        if (ranked.size() > named)
            line.append(", ").append(ranked.size() - named).append(" more");
        return line.toString();
    }

    private static final class Holder {
        final String owner;
        long bytes;
        int forks;
        boolean asked;

        Holder(String owner) {
            this.owner = owner;
        }
    }
}
