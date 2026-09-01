// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkGarbageTest {

    private static Path dir(Path root, String name) throws IOException {
        Path d = root.resolve(name);
        Files.createDirectories(d.resolve("bin"));
        Files.writeString(d.resolve("bin").resolve("java"), "x");
        Files.writeString(d.resolve("bin").resolve("javac"), "x");
        return d;
    }

    @Test
    void enqueue_then_drain_deletes_and_clears_queue(@TempDir Path tmp) throws IOException {
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path old = dir(jdks, "temurin-25.0.3");
        JdkOwnership.mark(old); // only jk's own installs are collectable

        JdkGarbage g = new JdkGarbage(jdks);
        g.enqueue(old);
        assertThat(jdks.resolve(".to-be-removed")).exists();

        g.drain();
        assertThat(old).doesNotExist();
        assertThat(jdks.resolve(".to-be-removed")).doesNotExist(); // nothing left → queue removed
    }

    @Test
    void never_enqueues_or_deletes_outside_the_jdk_root(@TempDir Path tmp) throws IOException {
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside);

        JdkGarbage g = new JdkGarbage(jdks);
        g.enqueue(outside);
        assertThat(jdks.resolve(".to-be-removed")).doesNotExist(); // refused
        g.drain();
        assertThat(outside).exists();
    }

    @Test
    void a_jdk_jk_does_not_own_is_never_collected(@TempDir Path tmp) throws IOException {
        // JK-2624. Under the root is not the same as ours: ~/.jdks is IntelliJ's shared root, so an
        // update that superseded a JDK the user installed there would have queued their install for
        // deletion. Refused at enqueue AND at drain — the queue is a file that outlives the process,
        // so a row written by an older jk reaches drain without ever passing enqueue.
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path theirs = dir(jdks, "temurin-25.0.3"); // no .jk-owned marker

        JdkGarbage g = new JdkGarbage(jdks);
        g.enqueue(theirs);
        assertThat(jdks.resolve(".to-be-removed")).as("refused at enqueue").doesNotExist();

        // Hand-written queue row, as an older jk would have left behind.
        Files.writeString(jdks.resolve(".to-be-removed"), theirs.toRealPath() + System.lineSeparator());
        g.drain();
        assertThat(theirs.resolve("bin").resolve("java")).as("refused at drain").exists();
    }

    @Test
    void drain_is_a_no_op_without_a_queue(@TempDir Path tmp) {
        assertThatCode(() -> new JdkGarbage(tmp).drain()).doesNotThrowAnyException();
    }
}
