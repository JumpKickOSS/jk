// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.util.FileLocks;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildSlotTest {

    private static BuildSlot take(Path checkout) {
        return ((BuildSlot.Taken) BuildSlot.take(checkout)).slot();
    }

    @Test
    void one_slot_per_checkout_and_the_holder_is_described(@TempDir Path dir) throws Exception {
        Path checkout = Files.createDirectories(dir.resolve("ws"));
        BuildSlot slot = take(checkout);
        assertThat(BuildSlot.holderOf(checkout, "fp", checkout.toString(), "g:a")
                        .buildNumber())
                .as("taken, number not yet known")
                .isZero();
        slot.describe(
                new InFlightBuilds.Hold(9L, 27L, "fp", "test", checkout.toString(), "g:a", 1234L, null, null, null));

        assertThat(BuildSlot.take(checkout)).as("held by this engine").isInstanceOf(BuildSlot.Held.class);
        InFlightBuilds.Hold holder = BuildSlot.holderOf(checkout, "fp", checkout.toString(), "g:a");
        assertThat(holder.buildNumber()).isEqualTo(27L);
        assertThat(holder.kind()).isEqualTo("test");
        assertThat(holder.startedAt()).isEqualTo(1234L);
        assertThat(holder.requestId()).as("not this engine's request").isZero();

        slot.close();
        assertThat(BuildSlot.holderOf(checkout, "fp", checkout.toString(), "g:a")
                        .buildNumber())
                .as("the description leaves with the slot")
                .isZero();
        BuildSlot again = take(checkout);
        again.close();
    }

    @Test
    void two_checkouts_have_two_slots(@TempDir Path dir) throws Exception {
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        try (BuildSlot slotA = take(a);
                BuildSlot slotB = take(b)) {
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

            assertThat(BuildSlot.take(checkout)).isInstanceOf(BuildSlot.Held.class);
            assertThat(BuildSlot.take(checkout))
                    .as("probing twice changes nothing")
                    .isInstanceOf(BuildSlot.Held.class);
            assertThat(BuildSlot.holderOf(checkout, "fp", checkout.toString(), null)
                            .buildNumber())
                    .isEqualTo(41L);
        } finally {
            try (OutputStream in = holder.getOutputStream()) {
                in.write('\n');
            }
            holder.waitFor();
        }
        assertThat(BuildSlot.take(checkout)).as("released with the process").isInstanceOf(BuildSlot.Taken.class);
    }

    /** Subprocess body: hold the checkout's slot until stdin closes. */
    public static final class HolderMain {
        public static void main(String[] args) throws Exception {
            Path checkout = Path.of(args[0]);
            if (!(FileLocks.tryHold(BuildSlot.lockFile(checkout)) instanceof FileLocks.Hold hold)) {
                System.out.println("not held");
                System.exit(2);
                return;
            }
            hold.write("pid=" + ProcessHandle.current().pid() + "\nbuild=41\nkind=build\nstarted=1\n");
            System.out.println("held");
            System.out.flush();
            System.in.read();
            hold.close();
        }
    }
}
