// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The image terminal, the shelf publish, a stop and the suite's counts ride module-finish as additive fields. */
class ProtoEventsModuleFinishTest {

    @Test
    void image_outcome_fields_round_trip_and_are_omitted_when_absent() {
        String plain = ProtoEvents.moduleFinish("/w/app", "g:app", true, 0, 12, true, false);
        assertThat(plain).doesNotContain("hasImage");

        String loaded = ProtoEvents.moduleFinish(
                "/w/app",
                "g:app",
                true,
                0,
                12,
                true,
                false,
                new ModuleOutcome.Image("reg.example/app:1.0", null, "app", "1.0", "docker", "reg.example/app:1.0"),
                null);
        assertThat(Jsonl.bool(loaded, "hasImage", false)).isTrue();
        assertThat(Jsonl.str(loaded, "imageRef")).isEqualTo("reg.example/app:1.0");
        assertThat(Jsonl.str(loaded, "imageName")).isEqualTo("app");
        assertThat(Jsonl.str(loaded, "imageVersion")).isEqualTo("1.0");
        assertThat(Jsonl.str(loaded, "imageTarball")).isNull();
        assertThat(Jsonl.str(loaded, "imageDaemonExe")).isEqualTo("docker");
        assertThat(ModuleFinishEvent.decode(loaded).image())
                .extracting(ModuleOutcome.Image::publishRef)
                .isEqualTo("reg.example/app:1.0");
    }

    @Test
    void install_round_trips_and_is_omitted_when_the_module_installed_nothing() {
        String plain = ProtoEvents.moduleFinish("/w/app", "g:app", true, 0, 12, true, false);
        assertThat(plain).doesNotContain("installCoord");
        assertThat(ModuleFinishEvent.decode(plain).installed()).isNull();

        ModuleOutcome.Installed installed = new ModuleOutcome.Installed(
                "g:app:1.0",
                "a".repeat(64),
                "b".repeat(64),
                InstallDestination.JK,
                ModuleOutcome.Installed.Change.REPLACED);
        String line = ProtoEvents.moduleFinish("/w/app", "g:app", true, 0, 12, true, false, null, installed);
        assertThat(Jsonl.str(line, "installCoord")).isEqualTo("g:app:1.0");
        assertThat(Jsonl.str(line, "installJarSha256")).isEqualTo("a".repeat(64));
        assertThat(Jsonl.str(line, "installPomSha256")).isEqualTo("b".repeat(64));
        assertThat(line).doesNotContain("hasImage");
        ModuleFinishEvent decoded = ModuleFinishEvent.decode(line);
        assertThat(decoded.installed()).isEqualTo(installed);
        assertThat(decoded.image()).isNull();
    }

    @Test
    void a_publish_outcome_carries_how_many_artifacts_were_already_there() {
        ModuleOutcome.Published published = new ModuleOutcome.Published(
                "https://repo.example/releases/", false, 16, 4, List.of(), List.of(), 0L, null, null, List.of());

        String line = ProtoEvents.moduleFinish(
                "/w/app", "g:app", true, 0, 12, 0, true, false, false, null, null, published, null);

        assertThat(ModuleFinishEvent.decode(line).published()).isEqualTo(published);
    }

    @Test
    void a_stopped_module_says_so_and_a_failed_one_carries_no_stopped_field() {
        String failed = ProtoEvents.moduleFinish("/w/a", "g:a", false, 1, 12, true, false);
        assertThat(failed).doesNotContain("stopped");
        assertThat(ModuleFinishEvent.decode(failed).stopped()).isFalse();

        String line =
                ProtoEvents.moduleFinish("/w/b", "g:b", false, 1, 12, 0, true, false, true, null, null, null, null);
        assertThat(Jsonl.bool(line, "stopped", false)).isTrue();
        assertThat(Jsonl.bool(line, "success", true)).isFalse();
        ModuleOutcome outcome = ModuleFinishEvent.decode(line).outcome(Path.of("/w/b"));
        assertThat(outcome.stopped()).isTrue();
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.dir()).isEqualTo(Path.of("/w/b"));
    }

    /** A suite replayed from its green stamp reaches the client with the tests it skipped. */
    @Test
    void the_suite_counts_round_trip_and_are_omitted_for_a_module_with_no_suite() {
        String plain = ProtoEvents.moduleFinish("/w/app", "g:app", true, 0, 12, false, false);
        assertThat(plain).doesNotContain("\"tests\"");
        assertThat(ModuleFinishEvent.decode(plain).tests()).isNull();

        ModuleOutcome.Tests tests = new ModuleOutcome.Tests(12, 2, false);
        String line = ModuleFinishEvent.of(
                        new ModuleOutcome("g:app", Path.of("/w/app"), true, 0, 12, false).withTests(tests))
                .encode();
        assertThat(line).contains("\"tests\":{\"total\":12,\"skipped\":2}");
        assertThat(ModuleFinishEvent.decode(line).outcome(Path.of("/w/app")).tests())
                .isEqualTo(tests);
    }

    @Test
    void a_replayed_suite_and_the_time_spent_waiting_round_trip() {
        String plain = ProtoEvents.moduleFinish("/w/app", "g:app", true, 0, 12, false, false);
        assertThat(plain).doesNotContain("waitedMs");
        assertThat(ModuleFinishEvent.decode(plain).waitedMillis()).isZero();

        ModuleOutcome.Tests replayed = new ModuleOutcome.Tests(12, 2, true);
        ModuleOutcome sent = new ModuleOutcome("g:app", Path.of("/w/app"), true, 0, 15_400, false)
                .withWaited(14_900)
                .withTests(replayed);
        String line = ModuleFinishEvent.of(sent).encode();
        assertThat(line).contains("\"waitedMs\":14900");
        assertThat(line).contains("\"tests\":{\"total\":12,\"skipped\":2,\"replayed\":true}");
        assertThat(ModuleFinishEvent.decode(line).outcome(Path.of("/w/app"))).isEqualTo(sent);
    }

    @Test
    void the_time_spent_waiting_is_never_more_than_the_module_took() {
        ModuleOutcome o = new ModuleOutcome("g:app", Path.of("/w/app"), true, 0, 900, true);
        assertThat(o.withWaited(5_000).waitedMillis()).isEqualTo(900);
        assertThat(o.withWaited(-3).waitedMillis()).isZero();
    }
}
