// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Guest;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.winhost.MachineSetup;
import cc.jumpkick.winhost.SetupMemo;
import cc.jumpkick.winhost.WindowsFromGuest;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk self setup-windows} installs JumpKick for Windows from a WSL distro and answers the offer. */
class SelfSetupWindowsCommandTest {

    @TempDir
    Path tmp;

    private final AtomicInteger installs = new AtomicInteger();

    private SelfSetupWindowsCommand command(Guest guest, @Nullable Path windowsJk, boolean installOk) {
        Path memo = tmp.resolve(SetupMemo.FILE);
        return new SelfSetupWindowsCommand(new SelfSetupWindowsCommand.Host() {
            @Override
            public Guest guest() {
                return guest;
            }

            @Override
            public @Nullable Path windowsJk() {
                return windowsJk;
            }

            @Override
            public MachineSetup.Result install(Consumer<String> progress) {
                installs.incrementAndGet();
                progress.accept("installing");
                return new MachineSetup.Result(
                        WindowsFromGuest.ROW, installOk, installOk ? "installed" : "could not install: offline");
            }

            @Override
            public Path memoFile() {
                return memo;
            }
        });
    }

    private static final Guest WSL = new Guest(Guest.Kind.WSL, "Ubuntu-26.10", true, "i");

    @Test
    void outside_a_wsl_guest_with_interop_it_refuses() {
        assertThat(command(Guest.NONE, null, true).run(Invocation.builder().build()))
                .isEqualTo(Exit.USAGE);
        assertThat(command(new Guest(Guest.Kind.WSL, "Ubuntu", false, "i"), null, true)
                        .run(Invocation.builder().build()))
                .isEqualTo(Exit.USAGE);
        assertThat(installs.get()).isZero();
    }

    @Test
    void it_installs_and_the_answer_stops_later_offers() {
        assertThat(command(WSL, null, true).run(Invocation.builder().build())).isEqualTo(Exit.SUCCESS);
        assertThat(installs.get()).isEqualTo(1);
        assertThat(WindowsFromGuest.answered(tmp.resolve(SetupMemo.FILE))).isTrue();
    }

    @Test
    void a_failed_install_fails_and_leaves_the_offer_unanswered() {
        assertThat(command(WSL, null, false).run(Invocation.builder().build())).isEqualTo(Exit.FAILURE);
        assertThat(WindowsFromGuest.answered(tmp.resolve(SetupMemo.FILE))).isFalse();
    }

    @Test
    void windows_with_jumpkick_installs_nothing_and_answers_the_offer() {
        Path jk = tmp.resolve("jk.exe");
        assertThat(command(WSL, jk, true).run(Invocation.builder().build())).isEqualTo(Exit.SUCCESS);
        assertThat(installs.get()).isZero();
        assertThat(WindowsFromGuest.answered(tmp.resolve(SetupMemo.FILE))).isTrue();
    }
}
