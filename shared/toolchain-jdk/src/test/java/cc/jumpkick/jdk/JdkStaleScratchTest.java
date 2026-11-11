// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A JDK half-unpacked by a process that died is reclaimed once it is old enough to be orphaned. */
class JdkStaleScratchTest {

    @Test
    void an_orphaned_stage_is_swept_and_a_fresh_one_or_an_install_is_kept(@TempDir Path tmp) throws Exception {
        Path jdksRoot = tmp.resolve("jdks");
        Path orphan = Files.createDirectories(jdksRoot.resolve(".stage-123/jdk-25/bin"));
        Files.writeString(orphan.resolve("java"), "x");
        Path orphanRoot = jdksRoot.resolve(".stage-123");
        Path fresh = Files.createDirectories(jdksRoot.resolve(".stage-456"));
        Path installed = Files.createDirectories(jdksRoot.resolve("temurin-25"));
        FileTime twoDaysAgo = FileTime.from(Clock.SYSTEM.instant().minus(Duration.ofDays(2)));
        Files.setLastModifiedTime(orphanRoot, twoDaysAgo);
        Files.setLastModifiedTime(installed, twoDaysAgo);

        JdkInstaller.sweepStaleScratch(jdksRoot);

        assertThat(orphanRoot).doesNotExist();
        assertThat(fresh).isDirectory();
        assertThat(installed).isDirectory();
    }
}
