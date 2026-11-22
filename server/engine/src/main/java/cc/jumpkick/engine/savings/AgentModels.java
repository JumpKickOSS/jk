// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import org.jspecify.annotations.Nullable;

/**
 * The model an agent was on when it acted, as its own session files say ({@link AgentUsage#models}):
 * the model of its last count at or before the action, else of its first count after it. Each agent's
 * files for a checkout are read once, from the earliest action {@link #note noted} for them.
 */
final class AgentModels {

    private final AgentUsage usage;
    private final Map<Key, Long> since = new HashMap<>();
    private final Map<Key, NavigableMap<Long, String>> read = new HashMap<>();

    private record Key(String client, String project) {}

    AgentModels(AgentUsage usage) {
        this.usage = usage;
    }

    /** That {@code client} acted in {@code project} at {@code at}: its files are read from there on. */
    void note(@Nullable String client, @Nullable String project, long at) {
        if (client == null || project == null) return;
        since.merge(new Key(client, project), at, Math::min);
    }

    /** The model {@code client} was on in {@code project} at {@code at}, or null when its files name none. */
    @Nullable
    String at(@Nullable String client, @Nullable String project, long at) {
        if (client == null || project == null) return null;
        Key key = new Key(client, project);
        NavigableMap<Long, String> models =
                read.computeIfAbsent(key, k -> usage.models(k.client(), k.project(), since.getOrDefault(k, at)));
        Map.Entry<Long, String> e = models.floorEntry(at);
        if (e == null) e = models.ceilingEntry(at);
        return e == null ? null : e.getValue();
    }
}
