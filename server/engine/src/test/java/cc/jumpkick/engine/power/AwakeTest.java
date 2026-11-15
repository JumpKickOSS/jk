// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.power;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.KeepAwake;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The machine is kept awake from the first job's start to the last job's end, on A/C under the
 * default, through each OS's probe and hold with the probes read from fakes and the helper faked.
 */
class AwakeTest {

    @TempDir
    Path supplies;

    private final FakeHelper helper = new FakeHelper();
    private final AtomicReference<KeepAwake> mode = new AtomicReference<>(KeepAwake.AC);
    private final List<String> logged = new ArrayList<>();

    private Awake awake(PowerHost host) {
        Awake a = new Awake(mode::get, () -> host, null);
        a.enable(logged::add);
        return a;
    }

    private void supply(String name, String type, @Nullable String online) throws IOException {
        Path d = Files.createDirectories(supplies.resolve(name));
        Files.writeString(d.resolve("type"), type + "\n");
        if (online != null) Files.writeString(d.resolve("online"), online + "\n");
    }

    private LinuxPower laptop(boolean mains) throws IOException {
        supply("BAT0", "Battery", null);
        supply("AC", "Mains", mains ? "1" : "0");
        return new LinuxPower(supplies, helper);
    }

    @Test
    void linux_on_ac_holds_an_idle_and_sleep_inhibitor_from_the_first_job_to_the_last_and_not_after()
            throws IOException {
        Awake a = awake(laptop(true));

        a.jobStarted(1);
        a.jobStarted(2);
        assertThat(a.holding()).isTrue();
        assertThat(helper.started).containsExactly(LinuxPower.INHIBIT);
        assertThat(LinuxPower.INHIBIT).startsWith("systemd-inhibit", "--what=idle:sleep");

        a.jobEnded(1);
        assertThat(a.holding()).as("a job still runs").isTrue();
        a.jobEnded(2);
        assertThat(a.holding())
                .as("released with the last job, not after the idle linger")
                .isFalse();
        assertThat(helper.alive()).as("the helper is reaped").isZero();
    }

    @Test
    void a_job_start_never_starts_the_helper_on_the_jobs_own_thread() throws Exception {
        List<Runnable> loops = new ArrayList<>();
        LinuxPower host = laptop(true);
        Awake a = new Awake(mode::get, () -> host, loops::add);
        a.enable(logged::add);

        a.jobStarted(1);
        assertThat(helper.started).as("the job's thread started no helper").isEmpty();
        assertThat(loops).hasSize(1);

        Thread loop = Thread.ofVirtual().start(loops.getFirst());
        long until = System.nanoTime() + 5_000_000_000L;
        while (!a.holding() && System.nanoTime() < until) Thread.sleep(10);
        assertThat(a.holding()).as("the loop took the hold").isTrue();
        a.jobEnded(1);
        assertThat(a.holding()).isFalse();
        loop.interrupt();
        loop.join();
    }

    @Test
    void linux_on_battery_holds_nothing_under_ac_and_holds_under_always() throws IOException {
        Awake a = awake(laptop(false));
        a.jobStarted(1);
        assertThat(a.holding()).isFalse();
        assertThat(helper.started).isEmpty();

        mode.set(KeepAwake.ALWAYS);
        a.check();
        assertThat(a.holding()).isTrue();
    }

    @Test
    void a_change_to_battery_mid_build_drops_the_hold_and_a_change_back_takes_it_again() throws IOException {
        Awake a = awake(laptop(true));
        a.jobStarted(1);
        assertThat(a.holding()).isTrue();

        Files.writeString(supplies.resolve("AC/online"), "0\n");
        a.check();
        assertThat(a.holding()).isFalse();
        assertThat(logged).anyMatch(l -> l.contains("battery"));

        Files.writeString(supplies.resolve("AC/online"), "1\n");
        a.check();
        assertThat(a.holding()).isTrue();
        assertThat(helper.started).hasSize(2);
    }

    @Test
    void a_linux_machine_with_no_system_battery_is_on_ac_and_a_mouse_battery_is_not_the_machines() throws IOException {
        assertThat(new LinuxPower(supplies.resolve("missing"), helper).source()).isEqualTo(PowerHost.Source.AC);
        Path mouse = Files.createDirectories(supplies.resolve("hidpp_battery_0"));
        Files.writeString(mouse.resolve("type"), "Battery\n");
        Files.writeString(mouse.resolve("scope"), "Device\n");
        assertThat(new LinuxPower(supplies, helper).source()).isEqualTo(PowerHost.Source.AC);
        supply("BAT1", "Battery", null);
        assertThat(new LinuxPower(supplies, helper).source()).isEqualTo(PowerHost.Source.BATTERY);
    }

    @Test
    void never_holds_nothing_and_neither_does_an_engine_that_did_not_enable_it() throws IOException {
        mode.set(KeepAwake.NEVER);
        Awake a = awake(laptop(true));
        a.jobStarted(1);
        assertThat(a.holding()).isFalse();

        mode.set(KeepAwake.ALWAYS);
        Awake off = new Awake(mode::get, () -> new LinuxPower(supplies, helper), null);
        off.jobStarted(1);
        assertThat(off.holding()).isFalse();
        assertThat(helper.started).isEmpty();
    }

    @Test
    void a_helper_that_exits_at_once_is_no_hold_and_is_said_once() throws IOException {
        helper.refuse = true;
        Awake a = awake(laptop(true));
        a.jobStarted(1);
        a.check();
        assertThat(a.holding()).isFalse();
        assertThat(logged)
                .filteredOn(l -> l.contains("cannot keep the machine awake"))
                .hasSize(1);
    }

    @Test
    void a_wsl_guest_or_a_container_only_detects() {
        Awake a = awake(new PowerHost.DetectOnly("a WSL guest cannot hold the Windows host awake"));
        mode.set(KeepAwake.ALWAYS);
        a.jobStarted(1);
        assertThat(a.holding()).isFalse();
        assertThat(logged).anyMatch(l -> l.contains("WSL guest"));
    }

    @Test
    void macos_reads_pmset_and_holds_with_caffeinate_watching_the_engine() {
        assertThat(MacPower.parse("Now drawing from 'AC Power'\n -InternalBattery-0 (id=1)\t100%; charged"))
                .isEqualTo(PowerHost.Source.AC);
        assertThat(MacPower.parse("Now drawing from 'Battery Power'\n -InternalBattery-0\t87%; discharging"))
                .isEqualTo(PowerHost.Source.BATTERY);
        assertThat(MacPower.parse(null)).isEqualTo(PowerHost.Source.UNKNOWN);

        AtomicReference<String> batt = new AtomicReference<>("Now drawing from 'AC Power'");
        Awake a = awake(new MacPower(batt::get, helper, 4242));
        a.jobStarted(1);
        assertThat(helper.started).containsExactly(List.of("caffeinate", "-i", "-s", "-w", "4242"));

        batt.set("Now drawing from 'Battery Power'");
        a.check();
        assertThat(a.holding()).isFalse();
        assertThat(helper.alive()).isZero();
    }

    @Test
    void windows_reads_the_power_status_and_holds_a_system_required_request() {
        assertThat(WindowsPower.source(1, 8)).isEqualTo(PowerHost.Source.AC);
        assertThat(WindowsPower.source(0, 1)).isEqualTo(PowerHost.Source.BATTERY);
        assertThat(WindowsPower.source(0, 128)).as("no system battery").isEqualTo(PowerHost.Source.AC);
        assertThat(WindowsPower.source(255, 255)).isEqualTo(PowerHost.Source.UNKNOWN);

        int[] status = {1, 8};
        List<String> requests = new ArrayList<>();
        List<String> cleared = new ArrayList<>();
        WindowsPower.Native calls = new WindowsPower.Native() {
            @Override
            public int[] powerStatus() {
                return status.clone();
            }

            @Override
            public PowerHost.Hold systemRequired(String why) {
                requests.add(why);
                return new PowerHost.Hold() {
                    private boolean open = true;

                    @Override
                    public boolean alive() {
                        return open;
                    }

                    @Override
                    public void close() {
                        open = false;
                        cleared.add(why);
                    }
                };
            }
        };
        Awake a = awake(new WindowsPower(calls));
        a.jobStarted(1);
        assertThat(requests).hasSize(1);
        assertThat(a.holding()).isTrue();

        status[0] = 0;
        a.check();
        assertThat(a.holding()).isFalse();
        assertThat(cleared).hasSize(1);
    }
}
