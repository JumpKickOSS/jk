// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A session's {@code episodes.jsonl}: one line per change of an episode, the last line of each id standing. */
final class EpisodeLines {

    private EpisodeLines() {}

    /** An episode line as compared for change: without the project id, which only some lines carry. */
    static String text(EpisodeInference.Stored e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("episode", e.episode().toMap());
        m.put("state", e.state());
        return MiniJson.write(m);
    }

    /** The episodes {@code lines} hold: the last line for each episode id. */
    static List<EpisodeInference.Stored> latest(List<Map<String, Object>> lines) {
        Map<String, EpisodeInference.Stored> byId = new LinkedHashMap<>();
        for (Map<String, Object> line : lines) {
            if (!(line.get("episode") instanceof Map<?, ?> em)) continue;
            Episode e = Episode.of(em);
            if (e.id().isBlank()) continue;
            Map<String, Object> state = new LinkedHashMap<>();
            if (line.get("state") instanceof Map<?, ?> sm) sm.forEach((k, v) -> state.put(String.valueOf(k), v));
            byId.remove(e.id());
            byId.put(e.id(), new EpisodeInference.Stored(e, state));
        }
        return new ArrayList<>(byId.values());
    }

    /** The number an episode id ends in, or 0. */
    static int numberOf(@Nullable String episode) {
        if (episode == null) return 0;
        int dot = episode.lastIndexOf('.');
        try {
            return dot > 0 ? Integer.parseInt(episode.substring(dot + 1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
