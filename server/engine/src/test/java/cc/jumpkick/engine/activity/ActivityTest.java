// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.config.Request;
import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionForms;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.engine.decisions.Decisions;
import cc.jumpkick.engine.notices.Notices;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Activity page's history: the change journal, the decision history and the bell's notices
 * merged newest first, each entry saying who, what, when and how it reached the person, filtered
 * and paged behind a cursor.
 */
class ActivityTest {

    @TempDir
    Path home;

    private final DecisionQueue queue = new DecisionQueue();
    private final Notices notices = new Notices();

    private ChangeJournal journal() {
        return new ChangeJournal(home.resolve("changes"));
    }

    private Activity activity() {
        return new Activity(this::journal, queue, notices);
    }

    private Decision ask(String kind, String question, @Nullable String session, @Nullable String project) {
        return queue.open(new DecisionQueue.Ask(
                kind,
                question,
                List.of("why it asks"),
                DecisionForms.confirm(kind, question, "Yes"),
                session,
                "claude-code 3f9a",
                null,
                project));
    }

    @Test
    void a_decision_answered_in_the_terminal_says_who_asked_where_it_was_answered_and_how_it_reached_the_person() {
        Decision d = ask("disk-clean", "Clean JumpKick's cache?", "s1", "/w/app");
        queue.notified(d.id(), Decisions.CHANNEL_NATIVE);
        queue.accept(d.id(), Map.of(DecisionForms.ANSWER, DecisionForms.YES), DecisionSurface.CLI);

        Activity.Entry e =
                activity().page(Activity.Filter.ALL, null, 10).entries().getFirst();

        assertThat(e.kind()).isEqualTo(Activity.DECISION);
        assertThat(e.title()).isEqualTo("Clean JumpKick's cache?");
        assertThat(e.who()).isEqualTo("claude-code 3f9a");
        assertThat(e.surface()).isEqualTo("cli");
        assertThat(e.channel()).isEqualTo("native");
        assertThat(e.href()).isEqualTo("#decision/" + d.id());
        assertThat(e.toMap())
                .containsEntry("outcome", "accepted")
                .containsEntry("answeredBy", "cli")
                .containsEntry("project", "/w/app")
                .containsEntry("session", "s1")
                .containsKey("answeredAt");
    }

    @Test
    void a_global_change_from_an_import_names_the_import_and_who_ran_it_with_its_diff() {
        Request.actingAs(ChangeActor.importOf("/w/app/pom.xml"), () -> journal()
                .record("config.toml", Change.EDIT, "added repository corp", "+[repositories.corp]\n"));

        Activity.Entry e =
                activity().page(Activity.Filter.ALL, null, 10).entries().getFirst();

        assertThat(e.kind()).isEqualTo(Activity.CHANGE);
        assertThat(e.title()).isEqualTo("Added repository corp");
        assertThat(e.who()).startsWith("import of /w/app/pom.xml by ");
        assertThat(e.surface()).isEqualTo("import");
        assertThat(e.project()).isEqualTo("/w/app/pom.xml");
        assertThat(e.href()).isEqualTo("#activity/" + e.id());
        assertThat(e.toMap()).containsEntry("what", "config.toml").containsEntry("diff", "+[repositories.corp]\n");
    }

    @Test
    void a_setup_run_is_a_setup_entry_and_so_is_its_decision() {
        Decision d = ask("setup", "Apply the recommended machine setup?", null, null);
        queue.accept(d.id(), Map.of(DecisionForms.ANSWER, DecisionForms.YES), DecisionSurface.DASHBOARD);
        Request.actingAs(ChangeActor.dashboard("machine setup"), () -> journal()
                .record("Windows host", Change.APPLY, "applied machine setup: 2 changes", "+ a\n+ b\n"));
        journal().record("JDK 25", Change.INSTALL, "installed JDK 25", null);

        List<Activity.Entry> setup = activity()
                .page(new Activity.Filter("setup", null, null, null), null, 10)
                .entries();

        assertThat(setup)
                .extracting(Activity.Entry::title)
                .containsExactlyInAnyOrder("Applied machine setup: 2 changes", "Apply the recommended machine setup?");
        assertThat(setup).allSatisfy(e -> assertThat(e.surface()).isEqualTo("dashboard"));
        assertThat(activity()
                        .page(new Activity.Filter("install", null, null, null), null, 10)
                        .entries())
                .singleElement()
                .satisfies(e -> assertThat(e.title()).isEqualTo("Installed JDK 25"));
    }

    @Test
    void filters_keep_one_project_one_session_and_one_surface_and_notifications_include_decisions_that_notified() {
        Decision told = ask("disk-clean", "Clean?", "s1", "/w/app");
        queue.notified(told.id(), Decisions.CHANNEL_BROWSER);
        Decision quiet = ask("jdk-uninstall", "Uninstall JDK 21?", "s2", "/w/other");
        queue.notified(quiet.id(), Decisions.CHANNEL_NONE);
        queue.decline(quiet.id(), DecisionSurface.HOST_FORM);
        notices.add("build", "The build finished", "#console", null);

        Activity a = activity();
        assertThat(a.page(new Activity.Filter(null, "/w/app", null, null), null, 10)
                        .entries())
                .extracting(Activity.Entry::title)
                .containsExactly("Clean?");
        assertThat(a.page(new Activity.Filter(null, null, "s2", null), null, 10).entries())
                .extracting(Activity.Entry::title)
                .containsExactly("Uninstall JDK 21?");
        assertThat(a.page(new Activity.Filter(null, null, null, "agent"), null, 10)
                        .entries())
                .extracting(Activity.Entry::title)
                .containsExactly("Uninstall JDK 21?");
        assertThat(a.page(new Activity.Filter("notification", null, null, null), null, 10)
                        .entries())
                .extracting(Activity.Entry::title)
                .containsExactlyInAnyOrder("The build finished", "Clean?");
        Activity.Facets f = a.facets();
        assertThat(f.projects()).containsExactly("/w/app", "/w/other");
        assertThat(f.surfaces()).contains("agent", "engine");
        assertThat(f.sessions()).extracting(m -> m.get("id")).containsExactlyInAnyOrder("s1", "s2");
    }

    @Test
    void a_decisions_notice_is_listed_once_as_its_decision() {
        notices.follow(queue);
        ask("disk-clean", "Clean?", null, null);

        assertThat(activity().page(Activity.Filter.ALL, null, 10).entries())
                .singleElement()
                .satisfies(e -> assertThat(e.kind()).isEqualTo(Activity.DECISION));
    }

    @Test
    void pages_follow_a_cursor_newest_first_without_repeating_or_skipping_an_entry() {
        ChangeJournal j = journal();
        for (int i = 0; i < 7; i++) j.record("file" + i, Change.EDIT, "edit " + i, null);
        Activity a = activity();

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Activity.Page p = a.page(Activity.Filter.ALL, cursor, 3);
            p.entries().forEach(e -> seen.add(e.title()));
            cursor = p.next();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactly("Edit 6", "Edit 5", "Edit 4", "Edit 3", "Edit 2", "Edit 1", "Edit 0");
    }
}
