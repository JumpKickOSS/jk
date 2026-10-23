// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteDiscoveryTest {

    @Test
    void anInputWrittenBeforeTheRunHeldStill(@TempDir Path tmp) throws Exception {
        Path read = Files.writeString(tmp.resolve("fixture.txt"), "one");
        long later = Clock.SYSTEM.millis() + 60_000;

        assertThat(SuiteDiscovery.settledBefore(Set.of(read), Set.of(), Set.of(), later))
                .isTrue();
    }

    @Test
    void anInputWrittenOnceTheRunStartedDidNot(@TempDir Path tmp) throws Exception {
        Path read = Files.writeString(tmp.resolve("fixture.txt"), "one");
        long earlier = Clock.SYSTEM.millis() - 60_000;

        assertThat(SuiteDiscovery.settledBefore(Set.of(read), Set.of(), Set.of(), earlier))
                .isFalse();
    }

    @Test
    void aProgramNamedByPathMustHoldStillToo(@TempDir Path tmp) throws Exception {
        Path tool = Files.writeString(tmp.resolve("tool"), "#!/bin/sh\n");
        long earlier = Clock.SYSTEM.millis() - 60_000;

        assertThat(SuiteDiscovery.settledBefore(Set.of(), Set.of(tool + " --flag"), Set.of(), earlier))
                .isFalse();
        assertThat(SuiteDiscovery.settledBefore(Set.of(), Set.of("git status"), Set.of(), earlier))
                .as("a bare name is keyed by what the PATH resolves it to")
                .isTrue();
    }
}
