// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.changes;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeJournalTest {

    @TempDir
    Path dir;

    @Test
    void an_edit_is_one_line_with_its_diff_and_its_actor() throws Exception {
        ChangeJournal journal = new ChangeJournal(dir, () -> 1_000L);
        ChangeActor shell = ChangeActor.shell(List.of("self", "setup-terminal", "--mode", "pill"));
        var change = ChangeActor.as(
                shell,
                () -> journal.recordEdit(
                        dir.resolve("config.toml"), "nerd-font set", "a = 1\n", "nerd-font = \"pill\"\n\na = 1\n"));

        assertThat(change).isPresent();
        List<String> lines = Files.readAllLines(dir.resolve(ChangeJournal.FILE), StandardCharsets.UTF_8);
        assertThat(lines).hasSize(1);
        Change read = journal.page(null, 10).entries().getFirst();
        assertThat(read.action()).isEqualTo(Change.EDIT);
        assertThat(read.diff()).contains("+nerd-font = \"pill\"").contains(" a = 1");
        assertThat(read.actor().kind()).isEqualTo(ChangeActor.SHELL);
        assertThat(read.actor().label()).isEqualTo("jk self setup-terminal --mode");
    }

    @Test
    void an_edit_that_changes_nothing_is_not_recorded() {
        ChangeJournal journal = new ChangeJournal(dir);
        assertThat(journal.recordEdit(dir.resolve("config.toml"), "same", "a = 1\n", "a = 1\n"))
                .isEmpty();
        assertThat(journal.page(null, 10).entries()).isEmpty();
    }

    @Test
    void a_credential_value_is_masked_in_the_diff_whatever_it_holds() {
        ChangeJournal journal = new ChangeJournal(dir);
        String after = """
                [repositories.nexus]
                url = "https://nexus.example/repo"
                username = "deploy"
                password = "hunter2-literal"
                token = "${NEXUS_TOKEN}"

                [repositories]
                other = { url = "https://o.example", password = "inline-secret" }
                """;
        Change c = journal.recordEdit(dir.resolve("config.toml"), "credential added", "", after)
                .orElseThrow();
        assertThat(c.diff())
                .doesNotContain("hunter2-literal")
                .doesNotContain("NEXUS_TOKEN")
                .doesNotContain("inline-secret")
                .contains("+password = \"***\"")
                .contains("+token = \"***\"")
                .contains("+username = \"deploy\"");
        assertThat(journal.page(null, 1).entries().getFirst().diff()).doesNotContain("hunter2");
    }

    @Test
    void the_shell_actor_never_keeps_an_option_value() {
        ChangeActor a = ChangeActor.shell(List.of("repo", "login", "nexus", "--password", "s3cret", "--token=abc"));
        assertThat(a.label()).isEqualTo("jk repo login nexus --password --token");
    }

    @Test
    void an_agent_actor_names_its_session_client_and_goal() {
        ChangeActor a = ChangeActor.agent("sess-1", "Cursor", "free disk space", "disk");
        assertThat(a.toMap())
                .containsEntry("kind", "agent")
                .containsEntry("session", "sess-1")
                .containsEntry("client", "Cursor")
                .containsEntry("goal", "free disk space");
        ChangeActor imported = ChangeActor.as(a, () -> ChangeActor.importOf("/work/app/pom.xml"));
        assertThat(imported.kind()).isEqualTo(ChangeActor.IMPORT);
        assertThat(imported.details()).containsEntry("by", a.label()).containsEntry("session", "sess-1");
    }

    @Test
    void pages_run_newest_first_and_the_cursor_reaches_every_entry() {
        AtomicLong now = new AtomicLong(1_000);
        ChangeJournal journal = new ChangeJournal(dir, now::incrementAndGet);
        for (int i = 0; i < 7; i++) journal.record("thing " + i, Change.DELETE, "deleted " + i, null);

        var first = journal.page(null, 3);
        assertThat(first.entries()).extracting(Change::what).containsExactly("thing 6", "thing 5", "thing 4");
        var second = journal.page(first.next(), 3);
        assertThat(second.entries()).extracting(Change::what).containsExactly("thing 3", "thing 2", "thing 1");
        var last = journal.page(second.next(), 3);
        assertThat(last.entries()).extracting(Change::what).containsExactly("thing 0");
        assertThat(last.next()).isNull();
    }

    @Test
    void a_full_file_rolls_over_and_both_files_stay_readable() throws Exception {
        ChangeJournal journal = new ChangeJournal(dir);
        journal.record("old", Change.CLEAN, "first", null);
        Path file = dir.resolve(ChangeJournal.FILE);
        Files.writeString(file, "x".repeat((int) ChangeJournal.ROLL_BYTES) + "\n", StandardOpenOption.APPEND);
        journal.record("new", Change.CLEAN, "second", null);

        assertThat(dir.resolve(ChangeJournal.ROLLED)).exists();
        assertThat(Files.size(file)).isLessThan(10_000);
        assertThat(journal.page(null, 10).entries()).extracting(Change::what).containsExactly("new", "old");
    }

    @Test
    void threads_appending_at_once_each_keep_their_entry() throws Exception {
        ChangeJournal journal = new ChangeJournal(dir);
        int threads = 8;
        int each = 40;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            writers.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < each; i++) journal.record("t" + id + "-" + i, Change.CLEAN, "s", null);
            }));
        }
        start.countDown();
        for (Thread w : writers) w.join();

        assertThat(journal.newestFirst()).hasSize(threads * each);
    }

    @Test
    void a_journal_that_cannot_be_written_costs_the_entry_only() throws Exception {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "a file where the directory should be");
        assertThat(new ChangeJournal(blocked).record("x", Change.DELETE, "y", null))
                .isEmpty();
    }

    @Test
    void the_line_shape_is_the_api_shape() {
        ChangeJournal journal = new ChangeJournal(dir, () -> 42L);
        Change c =
                journal.record("cache tier", Change.CLEAN, "freed 1 GB", null).orElseThrow();
        Map<String, Object> m = c.toMap();
        assertThat(m).containsKeys("kind", "id", "at", "what", "action", "summary", "actor");
        assertThat(m.get("kind")).isEqualTo("change");
        assertThat(c.at()).isEqualTo(42L);
    }
}
