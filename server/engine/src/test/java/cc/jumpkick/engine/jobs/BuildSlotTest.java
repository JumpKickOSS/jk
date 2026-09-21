// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.util.FileLocks;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildSlotTest {

    @Test
    void one_slot_per_checkout_and_the_holder_is_described(@TempDir Path dir) throws Exception {
        Path checkout = Files.createDirectories(dir.resolve("ws"));
        BuildSlot slot = BuildSlot.tryTake(checkout).orElseThrow();
        slot.describe(
                new InFlightBuilds.Hold(9L, 27L, "fp", "test", checkout.toString(), "g:a", 1234L, null, null, null));

        assertThat(BuildSlot.tryTake(checkout)).as("held by this engine").isEmpty();
        assertThat(BuildSlot.heldElsewhere(checkout)).isTrue();
        InFlightBuilds.Hold holder = BuildSlot.holderOf(checkout, "fp", checkout.toString(), "g:a");
        assertThat(holder.buildNumber()).isEqualTo(27L);
        assertThat(holder.kind()).isEqualTo("test");
        assertThat(holder.startedAt()).isEqualTo(1234L);
        assertThat(holder.requestId()).as("not this engine's request").isZero();

        slot.close();
        assertThat(BuildSlot.heldElsewhere(checkout)).isFalse();
        BuildSlot again = BuildSlot.tryTake(checkout).orElseThrow();
        again.close();
    }

    @Test
    void two_checkouts_have_two_slots(@TempDir Path dir) throws Exception {
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        try (BuildSlot slotA = BuildSlot.tryTake(a).orElseThrow();
                BuildSlot slotB = BuildSlot.tryTake(b).orElseThrow()) {
            assertThat(slotA).isNotNull();
            assertThat(slotB).isNotNull();
        }
    }

    /** The slot another engine holds refuses this one, and names the build it wrote. */
    @Test
    void a_slot_held_by_another_process_refuses_this_engine(@TempDir Path dir) throws Exception {
        Path checkout = Files.createDirectories(dir.resolve("ws"));
        String classpath = System.getProperty("java.class.path");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process holder = new ProcessBuilder(
                        java.toString(), "-cp", classpath, HolderMain.class.getName(), checkout.toString())
                .redirectErrorStream(true)
                .start();
        try {
            // The holder prints one line once it has the lock.
            int c;
            StringBuilder line = new StringBuilder();
            while ((c = holder.getInputStream().read()) >= 0 && c != '\n') line.append((char) c);
            assertThat(line.toString()).isEqualTo("held");

            assertThat(BuildSlot.tryTake(checkout)).isEmpty();
            assertThat(BuildSlot.heldElsewhere(checkout)).isTrue();
            assertThat(BuildSlot.holderOf(checkout, "fp", checkout.toString(), null)
                            .buildNumber())
                    .isEqualTo(41L);
        } finally {
            try (OutputStream in = holder.getOutputStream()) {
                in.write('\n');
            }
            holder.waitFor();
        }
        assertThat(BuildSlot.heldElsewhere(checkout))
                .as("released with the process")
                .isFalse();
    }

    /** Subprocess body: hold the checkout's slot until stdin closes. */
    public static final class HolderMain {
        public static void main(String[] args) throws Exception {
            Path checkout = Path.of(args[0]);
            Optional<FileLocks.Hold> hold = FileLocks.tryHold(BuildSlot.lockFile(checkout));
            if (hold.isEmpty()) {
                System.out.println("not held");
                System.exit(2);
            }
            hold.get().write("pid=" + ProcessHandle.current().pid() + "\nbuild=41\nkind=build\nstarted=1\n");
            System.out.println("held");
            System.out.flush();
            System.in.read();
            hold.get().close();
        }
    }
}
