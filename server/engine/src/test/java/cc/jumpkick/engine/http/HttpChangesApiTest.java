// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpChangesApiTest {

    @TempDir
    Path dir;

    @Test
    void pages_run_newest_first_and_next_reads_the_older_page() {
        ChangeJournal journal = new ChangeJournal(dir);
        for (int i = 0; i < 3; i++) journal.record("thing " + i, Change.DELETE, "deleted " + i, null);
        HttpChangesApi api = new HttpChangesApi(() -> journal);

        Map<?, ?> first = body(api.json(null, "2"));
        assertThat(whats(first)).containsExactly("thing 2", "thing 1");
        assertThat(((Map<?, ?>) ((List<?>) Objects.requireNonNull(first.get("entries"))).getFirst()).get("actor"))
                .isInstanceOf(Map.class);

        Map<?, ?> second = body(api.json((String) first.get("next"), "2"));
        assertThat(whats(second)).containsExactly("thing 0");
        assertThat(second.get("next")).isNull();
    }

    @Test
    void a_bad_limit_reads_the_default_page() {
        HttpChangesApi api = new HttpChangesApi(() -> new ChangeJournal(dir));
        assertThat(api.json("", "lots")).isEqualTo("{\"entries\":[],\"next\":null}");
    }

    private static Map<?, ?> body(String json) {
        return (Map<?, ?>) Objects.requireNonNull(MiniJson.parse(json));
    }

    private static List<Object> whats(Map<?, ?> body) {
        List<?> entries = (List<?>) Objects.requireNonNull(body.get("entries"));
        return entries.stream().map(e -> (Object) ((Map<?, ?>) e).get("what")).toList();
    }
}
