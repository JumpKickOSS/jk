// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The worker rows: what the engine resolves for each installed plugin worker, rendered so the
 * jar's provenance and the classpath it launches on are one line apart from the symptom.
 */
class DoctorWorkersTest {

    private static final String IMAGE_ROW = "jk-image-builder|0.13.3|jk-local|/store/repos/jk-local/cc/jumpkick/"
            + "jk-image-builder/0.13.3/jk-image-builder-0.13.3.jar|/store/repos/jk-local/cc/jumpkick/"
            + "jk-image-builder/0.13.3/jk-image-builder-0.13.3.pom|3|41||";

    private static final String BROKEN_ROW = "jk-formatter|0.13.3|jumpkick|/store/repos/jumpkick/cc/jumpkick/"
            + "jk-formatter/0.13.3/jk-formatter-0.13.3.jar||0|0|worker runtime dependency org.x:y:1 was not found|";

    private static final String REFUSED_ROW = "jk-grails|0.13.3|jk-local|/store/repos/jk-local/cc/jumpkick/"
            + "jk-grails/0.13.3/jk-grails-0.13.3.jar|/store/repos/jk-local/cc/jumpkick/jk-grails/0.13.3/"
            + "jk-grails-0.13.3.pom|2|7||/store/repos/jk-local/cc/jumpkick/jk-grails/0.13.3/jk-grails-0.13.3.jar"
            + " is the jk-grails worker but its root jk-plugin.toml describes plugin `spring-boot` (table"
            + " [spring-boot], worker jk-spring-boot) — a vendored sibling's descriptor took the jar root; the"
            + " jar is not registered. Reinstall it so its own descriptor sits at the root: `jk install` from"
            + " the jk checkout, or `jk storage clean --workers` and let the next build fetch the published jar.";

    @Test
    void each_installed_worker_is_one_row_naming_its_source_and_classpath_size() {
        DoctorCommand.Workers workers = DoctorCommand.workers(
                () -> CacheInventoryAck.workers(
                        List.of(IMAGE_ROW),
                        List.of(
                                "jk-image-builder|/store/repos/jk-local/cc/jumpkick/jk-image-builder/0.13.3/jk-image-builder-0.13.3.jar",
                                "jk-image-builder|/store/repos/central/com/google/guava/guava/33.7.1-jre/guava-33.7.1-jre.jar")));

        assertThat(workers.error()).isNull();
        assertThat(workers.rows()).hasSize(1);
        DoctorCommand.Worker w = workers.rows().get(0);
        assertThat(w.source()).isEqualTo("jk-local");
        assertThat(w.declared()).isEqualTo(3);
        assertThat(w.classpath()).hasSize(2).anyMatch(p -> p.endsWith("guava-33.7.1-jre.jar"));

        List<String> plain = strip(DoctorCommand.renderWorkers(workers, false, Theme.active()));
        assertThat(plain).hasSize(1);
        assertThat(plain.get(0))
                .contains("worker:")
                .contains("jk-image-builder 0.13.3")
                .contains("from jk-local")
                .contains("POM declares 3 deps")
                .contains("2 entries on the launch classpath");

        List<String> verbose = strip(DoctorCommand.renderWorkers(workers, true, Theme.active()));
        assertThat(verbose).hasSize(4);
        assertThat(verbose.get(1)).contains("pom ").contains("jk-image-builder-0.13.3.pom");
        assertThat(verbose.get(3)).contains("guava-33.7.1-jre.jar");
    }

    @Test
    void a_worker_whose_classpath_does_not_resolve_is_a_warning_carrying_the_engine_error() {
        DoctorCommand.Workers workers =
                DoctorCommand.workers(() -> CacheInventoryAck.workers(List.of(BROKEN_ROW), List.of()));

        DoctorCommand.Worker w = workers.rows().get(0);
        assertThat(w.error()).isEqualTo("worker runtime dependency org.x:y:1 was not found");
        assertThat(w.classpath()).isEmpty();
        List<String> plain = strip(DoctorCommand.renderWorkers(workers, false, Theme.active()));
        assertThat(plain.get(0))
                .startsWith("warn:")
                .contains("jk-formatter 0.13.3 from jumpkick")
                .contains("did not resolve: worker runtime dependency org.x:y:1 was not found");
    }

    @Test
    void a_worker_whose_descriptor_the_loader_refused_is_a_warning_naming_the_descriptor_and_the_fix() {
        DoctorCommand.Workers workers =
                DoctorCommand.workers(() -> CacheInventoryAck.workers(List.of(REFUSED_ROW), List.of()));

        DoctorCommand.Worker w = workers.rows().get(0);
        assertThat(w.refused()).contains("describes plugin `spring-boot`");
        assertThat(w.error()).isNull();
        List<String> plain = strip(DoctorCommand.renderWorkers(workers, false, Theme.active()));
        assertThat(plain).hasSize(1);
        assertThat(plain.get(0))
                .startsWith("warn:")
                .contains("jk-grails 0.13.3 from jk-local")
                .contains("describes plugin `spring-boot` (table [spring-boot], worker jk-spring-boot)")
                .contains("`jk install` from the jk checkout, or `jk storage clean --workers`");
        assertThat(DoctorCommand.workersJson(workers))
                .contains("\"refused\":\"")
                .contains("describes plugin `spring-boot`");
    }

    @Test
    void no_workers_and_no_engine_each_read_as_one_honest_row() {
        DoctorCommand.Workers none = DoctorCommand.workers(() -> CacheInventoryAck.workers(List.of(), List.of()));
        assertThat(strip(DoctorCommand.renderWorkers(none, false, Theme.active()))
                        .get(0))
                .startsWith("ok:")
                .contains("none installed in the store");

        DoctorCommand.Workers unreachable = DoctorCommand.workers(() -> {
            throw new IOException("jk engine: could not start");
        });
        assertThat(unreachable.error()).contains("could not start");
        assertThat(strip(DoctorCommand.renderWorkers(unreachable, false, Theme.active()))
                        .get(0))
                .startsWith("warn:")
                .contains("workers")
                .contains("could not start");
    }

    @Test
    void the_json_member_carries_each_worker_with_its_classpath_array() {
        DoctorCommand.Workers workers = DoctorCommand.workers(
                () -> CacheInventoryAck.workers(
                        List.of(IMAGE_ROW, BROKEN_ROW),
                        List.of(
                                "jk-image-builder|/store/repos/central/com/google/guava/guava/33.7.1-jre/guava-33.7.1-jre.jar")));

        String json = DoctorCommand.workersJson(workers);

        assertThat(json).startsWith("[").endsWith("]");
        assertThat(json)
                .contains("\"artifact\":\"jk-image-builder\"")
                .contains("\"source\":\"jk-local\"")
                .contains("\"declared\":3")
                .contains(
                        "\"classpath\":[\"/store/repos/central/com/google/guava/guava/33.7.1-jre/guava-33.7.1-jre.jar\"]")
                .contains("\"artifact\":\"jk-formatter\"")
                .contains("\"error\":\"worker runtime dependency org.x:y:1 was not found\"");
        assertThat(DoctorCommand.workersJson(new DoctorCommand.Workers(List.of(), "engine query failed: down")))
                .isEqualTo("{\"error\":\"engine query failed: down\"}");
    }

    private static List<String> strip(List<String> lines) {
        return lines.stream().map(TestAnsi::strip).toList();
    }

    @Test
    void with_no_engine_running_the_row_says_so_and_how_to_get_the_rows() {
        DoctorCommand.Workers down = DoctorCommand.workers(() -> {
            throw new EngineClient.EngineNotRunningException();
        });

        assertThat(down.rows()).isEmpty();
        String row =
                strip(DoctorCommand.renderWorkers(down, false, Theme.active())).get(0);
        assertThat(row)
                .startsWith("warn:")
                .contains("workers")
                .contains("engine not running")
                .contains("`jk engine start`")
                .contains("`jk doctor --engine`");
        assertThat(DoctorCommand.workersJson(down)).contains("\"error\":\"engine not running");

        RepoStores.Stores repos = DoctorCommand.queryRepos(() -> {
            throw new EngineClient.EngineNotRunningException();
        });
        assertThat(repos.rows()).isEmpty();
        assertThat(repos.error()).isEqualTo(DoctorCommand.ENGINE_NOT_RUNNING);
    }

    @Test
    void no_engine_flag_skips_the_engine_answered_rows_by_name() {
        DoctorCommand.Workers skipped = new DoctorCommand.Workers(List.of(), DoctorCommand.ROWS_SKIPPED);
        assertThat(strip(DoctorCommand.renderWorkers(skipped, false, Theme.active()))
                        .get(0))
                .contains("workers")
                .contains("skipped (--no-engine)");
    }

    private static final String LIVE = "ab12cd34ef56" + "0".repeat(52);
    private static final String OLD = "ffffffffffff" + "0".repeat(52);

    private static String shelved(String artifact, String source, String packagedBy) {
        return artifact + "|0.13.3|" + source + "|/store/repos/" + source + "/cc/jumpkick/" + artifact + "/0.13.3/"
                + artifact + "-0.13.3.jar|/store/repos/" + source + "/cc/jumpkick/" + artifact + "/0.13.3/" + artifact
                + "-0.13.3.pom|1|2|||" + packagedBy;
    }

    @Test
    void a_shelf_packaged_by_the_pointed_engine_is_one_ok_row() {
        DoctorCommand.Workers workers = DoctorCommand.workers(() -> CacheInventoryAck.workers(
                List.of(shelved("jk-image-builder", "jk-local", LIVE), shelved("jk-formatter", "jk-local", LIVE)),
                List.of()));

        assertThat(workers.rows()).allSatisfy(w -> assertThat(w.packagedBy()).isEqualTo(LIVE));
        List<String> rows = strip(DoctorCommand.renderShelf(workers, Optional.of(LIVE), Theme.active()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).startsWith("ok:").contains("shelf").contains("engine the home names (ab12cd34ef56)");
        assertThat(DoctorCommand.workersJson(workers)).contains("\"packagedBy\":\"" + LIVE + "\"");
    }

    @Test
    void a_shelf_packaged_by_another_engine_names_both_engines_the_workers_and_the_fix() {
        DoctorCommand.Workers workers = DoctorCommand.workers(() -> CacheInventoryAck.workers(
                List.of(
                        shelved("jk-image-builder", "jk-local", OLD),
                        shelved("jk-formatter", "jk-local", LIVE),
                        shelved("jk-grails", "jk-local", OLD)),
                List.of()));

        List<String> rows = strip(DoctorCommand.renderShelf(workers, Optional.of(LIVE), Theme.active()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .startsWith("warn:")
                .contains("2 workers packaged by engine ffffffffffff")
                .contains("home names engine ab12cd34ef56")
                .contains("jk-image-builder, jk-grails")
                .contains("run `jk install`");
    }

    @Test
    void the_shelf_row_is_silent_without_a_recorded_packager_or_a_pointer() {
        DoctorCommand.Workers published = DoctorCommand.workers(() ->
                CacheInventoryAck.workers(List.of(shelved("jk-image-builder", "jumpkick", OLD), IMAGE_ROW), List.of()));
        assertThat(DoctorCommand.renderShelf(published, Optional.of(LIVE), Theme.active()))
                .isEmpty();

        DoctorCommand.Workers local = DoctorCommand.workers(
                () -> CacheInventoryAck.workers(List.of(shelved("jk-image-builder", "jk-local", OLD)), List.of()));
        assertThat(DoctorCommand.renderShelf(local, Optional.empty(), Theme.active()))
                .isEmpty();
        assertThat(DoctorCommand.renderShelf(
                        new DoctorCommand.Workers(List.of(), "engine not running"), Optional.of(LIVE), Theme.active()))
                .isEmpty();
    }
}
