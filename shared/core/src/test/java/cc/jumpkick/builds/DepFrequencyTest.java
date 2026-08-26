// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DepFrequencyTest {

    @Test
    void observe_replaces_same_project_without_double_count(@TempDir Path buildsRoot) throws Exception {
        DepFrequency once = DepFrequency.empty()
                .observe("proj-a", Set.of("jspecify", "guava"))
                .observe("proj-b", Set.of("jspecify"));
        once.save(buildsRoot);

        DepFrequency again = DepFrequency.load(buildsRoot).observe("proj-a", Set.of("jspecify", "guava"));
        again.save(buildsRoot);

        DepFrequency loaded = DepFrequency.load(buildsRoot);
        assertThat(loaded.top(10, Set.of())).containsExactly("jspecify", "guava");
        assertThat(loaded.projects().get("proj-a")).containsExactly("guava", "jspecify");
    }

    @Test
    void observe_updates_votes_when_deps_change(@TempDir Path buildsRoot) throws Exception {
        DepFrequency.load(buildsRoot)
                .observe("p1", Set.of("jspecify", "guava"))
                .observe("p2", Set.of("jspecify", "guava"))
                .save(buildsRoot);

        DepFrequency.load(buildsRoot)
                .observe("p1", Set.of("jspecify", "lombok"))
                .save(buildsRoot);

        DepFrequency loaded = DepFrequency.load(buildsRoot);
        assertThat(loaded.top(10, Set.of())).containsExactly("jspecify", "guava", "lombok");
        assertThat(loaded.projects().get("p1")).containsExactly("jspecify", "lombok");
    }

    @Test
    void top_ranks_by_count_then_name_respects_exclude_and_cap(@TempDir Path buildsRoot) throws Exception {
        DepFrequency freq = DepFrequency.empty()
                .observe("a", Set.of("zebra", "alpha", "common"))
                .observe("b", Set.of("common", "beta"))
                .observe("c", Set.of("common", "alpha"));

        assertThat(freq.top(10, Set.of())).containsExactly("common", "alpha", "beta", "zebra");
        assertThat(freq.top(2, Set.of())).containsExactly("common", "alpha");
        assertThat(freq.top(10, Set.of("common", "alpha"))).containsExactly("beta", "zebra");
        assertThat(freq.top(0, Set.of())).isEmpty();
    }

    @Test
    void load_missing_file_is_empty(@TempDir Path buildsRoot) {
        assertThat(DepFrequency.load(buildsRoot).projects()).isEmpty();
        assertThat(DepFrequency.file(buildsRoot))
                .isEqualTo(buildsRoot.resolve("projects").resolve(DepFrequency.FILE_NAME));
    }

    @Test
    void round_trip_quotes_colon_deps(@TempDir Path buildsRoot) throws Exception {
        DepFrequency.empty()
                .observe("id-1", Set.of("com.example:widget", "jspecify"))
                .save(buildsRoot);

        Path file = DepFrequency.file(buildsRoot);
        assertThat(Files.isRegularFile(file)).isTrue();
        String text = Files.readString(file);
        assertThat(text).contains("schema = 1");
        assertThat(text).contains("[projects.\"id-1\"]");
        assertThat(text).contains("\"com.example:widget\"");

        DepFrequency loaded = DepFrequency.load(buildsRoot);
        assertThat(loaded.projects().get("id-1")).containsExactly("com.example:widget", "jspecify");
    }

    @Test
    void round_trip_survives_quotes_backslashes_controls_and_non_ascii(@TempDir Path buildsRoot) throws Exception {
        String project = "id \"quoted\" \\slash\tand\nnewline é";
        String dep = "com.exàmple:art\"if\\act\twith\ncontrols";
        DepFrequency.empty().observe(project, Set.of(dep, "plain")).save(buildsRoot);

        DepFrequency loaded = DepFrequency.load(buildsRoot);
        assertThat(loaded.projects().get(project)).containsExactly(dep, "plain");
    }
}
