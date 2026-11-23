// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.test.SuiteReads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteDiscoveryTest {

    @Test
    void anInputWrittenBeforeTheRunHeldStill(@TempDir Path tmp) throws Exception {
        Path read = Files.writeString(tmp.resolve("fixture.txt"), "one");
        long later = Clock.SYSTEM.millis() + 60_000;

        assertThat(SuiteDiscovery.settledBefore(Set.of(read), Set.of(), Set.of(), tmp, later))
                .isTrue();
    }

    @Test
    void anInputWrittenOnceTheRunStartedDidNot(@TempDir Path tmp) throws Exception {
        Path read = Files.writeString(tmp.resolve("fixture.txt"), "one");
        long earlier = Clock.SYSTEM.millis() - 60_000;

        assertThat(SuiteDiscovery.settledBefore(Set.of(read), Set.of(), Set.of(), tmp, earlier))
                .isFalse();
    }

    @Test
    void aProgramNamedByPathMustHoldStillToo(@TempDir Path tmp) throws Exception {
        Path tool = Files.writeString(tmp.resolve("tool"), "#!/bin/sh\n");
        long earlier = Clock.SYSTEM.millis() - 60_000;

        assertThat(SuiteDiscovery.settledBefore(Set.of(), Set.of(tool.toString()), Set.of(), tmp, earlier))
                .isFalse();
        assertThat(SuiteDiscovery.settledBefore(Set.of(), Set.of("git status"), Set.of(), tmp, earlier))
                .as("a bare name is keyed by what the PATH resolves it to")
                .isTrue();
    }

    @Tag("slow")
    @Test
    void a_program_whose_path_holds_a_space_that_held_still_moved_nothing(@TempDir Path tmp) throws Exception {
        Path tool = Files.writeString(
                Files.createDirectories(tmp.resolve("Program Files").resolve("bin"))
                        .resolve("tool"),
                "#!/bin/sh\n");
        long started = startAfterWrites();

        assertThat(SuiteDiscovery.moved(Set.of(), Set.of(tool.toString()), Set.of(), tmp, started))
                .as("the recorded program is read whole, not cut at its space")
                .isNull();
    }

    @Tag("slow")
    @Test
    void a_hard_link_the_build_makes_is_not_a_write(@TempDir Path tmp) throws Exception {
        Path read = Files.writeString(tmp.resolve("worker.pom"), "<project/>");
        long started = startAfterWrites();
        Files.createLink(tmp.resolve("linked.pom"), read);

        assertThat(SuiteDiscovery.moved(Set.of(read), Set.of(), Set.of(tmp), tmp, started))
                .as("the link moves the change time, not the bytes")
                .isNull();
    }

    @Tag("slow")
    @Test
    void an_entry_made_and_removed_during_the_run_leaves_the_listing_as_it_was(@TempDir Path tmp) throws Exception {
        Path read = Files.writeString(tmp.resolve(".gitconfig"), "[user]\n");
        long started = startAfterWrites();
        Files.delete(Files.writeString(tmp.resolve(".lock"), "x"));

        assertThat(SuiteDiscovery.moved(Set.of(read), Set.of(), Set.of(), tmp, started))
                .isNull();
    }

    @Tag("slow")
    @Test
    void an_entry_added_during_the_run_moves_the_listing(@TempDir Path tmp) throws Exception {
        Path read = Files.writeString(tmp.resolve("a.txt"), "a");
        long started = startAfterWrites();
        Path added = Files.writeString(tmp.resolve("b.txt"), "b");

        assertThat(SuiteDiscovery.moved(Set.of(read), Set.of(), Set.of(), tmp, started))
                .isEqualTo(added);
    }

    @Tag("slow")
    @Test
    void a_modules_build_output_appearing_is_not_a_listing_change(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("web"));
        Path read = Files.writeString(module.resolve("jk.toml"), "name = \"web\"\n");
        long started = startAfterWrites();
        Files.createDirectories(module.resolve("target"));

        assertThat(SuiteDiscovery.moved(Set.of(read), Set.of(), Set.of(), tmp, started))
                .isNull();
    }

    @Test
    void a_listing_outside_the_workspace_or_in_a_build_output_is_not_keyed(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path module = Files.createDirectories(ws.resolve("plugin"));
        Files.writeString(module.resolve("jk.toml"), "name = \"plugin\"\n");
        Path outside = Files.createDirectories(tmp.resolve("home"));

        assertThat(SuiteReads.keysListing(module, ws)).isTrue();
        assertThat(SuiteReads.keysListing(module.resolve("target"), ws)).isFalse();
        assertThat(SuiteReads.keysListing(module.resolve("target/classes/a"), ws))
                .isFalse();
        assertThat(SuiteReads.keysListing(outside, ws)).isFalse();
    }

    @Tag("slow")
    @Test
    void git_swapping_its_index_while_a_suite_runs_moves_nothing_it_is_keyed_on(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path git = Files.createDirectories(ws.resolve(".git"));
        Path head = Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(git.resolve("index"), "one");
        long started = startAfterWrites();
        Path lock = Files.writeString(git.resolve("index.lock"), "two");
        AtomicWrites.moveInto(lock, git.resolve("index"));

        assertThat(SuiteReads.keysListing(git, ws)).isFalse();
        assertThat(SuiteReads.keysListing(git.resolve("refs/heads"), ws)).isFalse();
        assertThat(SuiteDiscovery.moved(Set.of(head), Set.of(), Set.of(), ws, started))
                .isNull();
    }

    @Test
    void the_moved_warning_names_the_input_when_one_carries_a_write(@TempDir Path tmp) throws Exception {
        Path added =
                Files.writeString(Files.createDirectories(tmp.resolve("data")).resolve("b.txt"), "b");

        assertThat(SuiteDiscovery.movedWarning(added, tmp))
                .isEqualTo("an input of run-tests changed while it ran: data/b.txt; the next build runs it again");
        assertThat(SuiteDiscovery.movedWarning(null, tmp))
                .as("the key moved and no input it was found to read carries a write")
                .isEqualTo("an input of run-tests changed while it ran; the next build runs it again");
    }

    /** A start time every file written so far precedes, on any clock grain. */
    private static long startAfterWrites() throws InterruptedException {
        Thread.sleep(1_100);
        return Clock.SYSTEM.millis();
    }
}
