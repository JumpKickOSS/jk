// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Recorded sessions replay into the episodes and titles a supervisor should see. Each fixture under
 * {@code src/test/resources/sessions/} is one session: its facts in time order, then the episodes it
 * must infer.
 *
 * <pre>
 * +m:ss run &lt;project&gt; green|red [&lt;N&gt;s] [Class#method ...]
 * +m:ss query|callers|read|find &lt;project&gt; &lt;text&gt;
 * +m:ss rename &lt;project&gt; &lt;T.m&gt;
 * +m:ss add|remove|pin &lt;project&gt; &lt;g:a&gt; ...
 * +m:ss update &lt;project&gt; &lt;pins&gt;
 * +m:ss other &lt;project&gt;
 * +m:ss end
 * expect &lt;number&gt; &lt;outcome&gt; &lt;title&gt;
 * </pre>
 *
 * A project {@code app} is the checkout {@code /ws/app}; a run's module is the project's name.
 */
class EpisodeFixturesTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "red-streak-ends-green",
                "idle-gap",
                "project-switch",
                "title-deps",
                "title-remove",
                "title-update",
                "title-rename",
                "title-fixing-first",
                "title-working",
                "session-end"
            })
    void the_session_replays_into_its_episodes(String name) throws IOException {
        List<String> expected = new ArrayList<>();
        EpisodeInference inference = new EpisodeInference("mcp-3f9a-1", EpisodeInference.DEFAULT_IDLE_GAP_MILLIS);
        for (String raw : read(name + ".session")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("expect ")) {
                expected.add(line.substring("expect ".length()));
                continue;
            }
            String[] t = line.split(" ");
            long at = offset(t[0]);
            if (t[1].equals("end")) {
                inference.end();
                continue;
            }
            Fact fact = fact(at, t);
            inference.fold(inference.place(fact.at(), fact.project()).episode(), fact);
        }
        List<String> actual = new ArrayList<>();
        for (Episode e : inference.episodes()) actual.add(e.number() + " " + e.outcome() + " " + e.title());
        assertThat(actual).as(name).containsExactlyElementsOf(expected);
        for (Episode e : inference.episodes()) {
            assertThat(e.titleSource()).isEqualTo(Episode.INFERRED);
            assertThat(e.id()).isEqualTo("mcp-3f9a-1." + e.number());
        }
    }

    private static Fact fact(long at, String[] t) {
        String verb = t[1];
        String project = "/ws/" + t[2];
        List<String> rest = Arrays.asList(t).subList(3, t.length);
        return switch (verb) {
            case "run" -> {
                boolean ok = rest.getFirst().equals("green");
                long millis = 0;
                List<String> tests = new ArrayList<>();
                for (String s : rest.subList(1, rest.size())) {
                    if (s.matches("\\d+s")) millis = Long.parseLong(s.substring(0, s.length() - 1)) * 1000;
                    else tests.add(s);
                }
                yield Fact.run(at, millis, project, t[2], ok, tests);
            }
            case "query", "callers", "read", "find" ->
                Fact.action(at, 0, project, Fact.Kind.GRAPH, true, String.join(" ", rest), 0);
            case "rename" -> Fact.action(at, 0, project, Fact.Kind.RENAME, true, rest.getFirst(), 0);
            case "add" -> Fact.action(at, 0, project, Fact.Kind.ADD, true, rest.getFirst(), rest.size());
            case "remove" -> Fact.action(at, 0, project, Fact.Kind.REMOVE, true, rest.getFirst(), rest.size());
            case "pin" -> Fact.action(at, 0, project, Fact.Kind.PIN, true, rest.getFirst(), rest.size());
            case "update" -> Fact.action(at, 0, project, Fact.Kind.UPDATE, true, "", Integer.parseInt(rest.getFirst()));
            case "other" -> Fact.action(at, 0, project, Fact.Kind.OTHER, true, "", 0);
            default -> throw new IllegalArgumentException("unknown fact " + verb);
        };
    }

    /** {@code +m:ss} or {@code +h:mm:ss} as millis from the session's start. */
    private static long offset(String token) {
        String[] parts = token.substring(1).split(":");
        long seconds = 0;
        for (String p : parts) seconds = seconds * 60 + Long.parseLong(p);
        return 1_700_000_000_000L + seconds * 1000;
    }

    private static List<String> read(String file) throws IOException {
        try (InputStream in = EpisodeFixturesTest.class.getResourceAsStream("/sessions/" + file)) {
            if (in == null) throw new AssertionError("missing test resource sessions/" + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}
