// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.StatIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A stat identity tells apart any two contents a tool can leave behind the same size and mtime.
 * Here, beside the Windows reader it loads, so the Windows lane checks the change time and file id.
 */
class StatIdentityTest {

    @Test
    void a_same_size_rewrite_that_restores_the_mtime_is_a_different_identity(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("Greet.java");
        Files.writeString(f, "hello");
        FileTime mtime = Files.getLastModifiedTime(f);
        StatIdentity before = StatIdentity.of(f);
        Thread.sleep(20);

        Files.writeString(f, "HELLO");
        Files.setLastModifiedTime(f, mtime);

        StatIdentity after = StatIdentity.of(f);
        assertThat(after.size()).isEqualTo(before.size());
        assertThat(after.mtimeNanos()).isEqualTo(before.mtimeNanos());
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void a_copy_over_the_file_that_preserves_its_times_is_a_different_identity(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Path other = tmp.resolve("b.txt");
        Files.writeString(f, "one");
        Files.writeString(other, "two");
        Files.setLastModifiedTime(other, Files.getLastModifiedTime(f));
        StatIdentity before = StatIdentity.of(f);
        Thread.sleep(20);

        Files.copy(other, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);

        assertThat(StatIdentity.of(f)).isNotEqualTo(before);
    }

    @Test
    void an_untouched_file_keeps_its_identity_whichever_way_it_is_read(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("x");
        Files.writeString(f, "x");
        BasicFileAttributes attrs = Files.readAttributes(f, BasicFileAttributes.class);

        assertThat(StatIdentity.of(f, attrs)).isEqualTo(StatIdentity.of(f));
        assertThat(StatIdentity.of(f).ctimeNanos())
                .as("this platform reports a change time")
                .isNotZero();
        assertThat(StatIdentity.of(f).fileKey())
                .as("this platform reports a file id")
                .isNotEmpty();
    }

    @Test
    void an_identity_survives_its_encoding(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("x");
        Files.writeString(f, "x");
        StatIdentity id = StatIdentity.of(f);

        assertThat(StatIdentity.decode(id.encode())).isEqualTo(id);
        assertThat(StatIdentity.decode("not an identity")).isNull();
    }

    @Test
    void on_windows_the_by_name_identity_is_the_handle_identity(@TempDir Path tmp) throws Exception {
        assumeTrue(Os.isWindows(), "the by-name call is Windows'");
        WindowsFileIds ids = new WindowsFileIds();
        Path file = Files.writeString(tmp.resolve("a.txt"), "one");
        Path link = Files.createLink(tmp.resolve("b.txt"), file);
        for (Path p : List.of(file, link, tmp)) {
            StatIdentity byName = ids.stat(p);
            assumeTrue(byName != null, "this Windows predates GetFileInformationByName");
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            StatIdentity.WindowsIds handle = Objects.requireNonNull(ids.read(p, false), "handle path");
            assertThat(byName)
                    .as(p.getFileName().toString())
                    .isEqualTo(new StatIdentity(
                            p.equals(tmp) ? byName.size() : attrs.size(),
                            attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                            handle.changeTimeNanos(),
                            handle.fileKey()));
        }
    }
}
