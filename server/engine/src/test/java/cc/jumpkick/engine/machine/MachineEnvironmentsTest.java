// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.machine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.winhost.ReleasePin;
import cc.jumpkick.winhost.WslDistros;
import cc.jumpkick.winhost.WslSetup;
import cc.jumpkick.winhost.WslShell;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one operation behind every surface: names are taken only as WSL lists them, distros install
 * one after another and a failing one leaves the next to install, each reports its steps, and an
 * install ends once the distro's engine pairs with the front door.
 */
class MachineEnvironmentsTest {

    private static final WslDistros.Distro UBUNTU = new WslDistros.Distro("Ubuntu-26.10", 2, true, true, null);
    private static final WslDistros.Distro BROKEN = new WslDistros.Distro("Broken", 2, false, false, null);
    private static final WslDistros.Distro STOPPED = new WslDistros.Distro("Fedora", 2, false, false, null);

    @TempDir
    Path scratch;

    /** Distros that answer the probe with a ready user, except {@code Broken}, whose installer fails. */
    final List<List<String>> ran = new CopyOnWriteArrayList<>();

    final Set<String> paired = ConcurrentHashMap.newKeySet();

    final WslShell shell = (List<String> argv, Map<String, String> env, Duration limit, Consumer<String> out) -> {
        ran.add(argv);
        String distro = argv.get(2);
        String script = argv.getLast();
        switch (script) {
            case "./probe.sh" ->
                List.of("uid=1000", "has=bash", "has=curl", "has=openssl", "has=gunzip", "arch=x86_64")
                        .forEach(out);
            case "./install.sh" -> {
                out.accept("* Installing JumpKick into ~/.jk/bin");
                if (distro.equals("Broken")) {
                    out.accept("x jk 0.51.1 publishes no native linux_x86_64 client");
                    return 1;
                }
            }
            case "./start.sh" -> paired.add(distro);
            default -> {}
        }
        return 0;
    };

    final WslSetup.Downloads downloads = new WslSetup.Downloads() {
        @Override
        public byte[] installer() {
            return "echo".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] client(String version, String arch) {
            return new byte[0];
        }

        @Override
        public byte[] engine(String version) {
            return new byte[0];
        }
    };

    final AtomicLong now = new AtomicLong(1_000);

    final Clock clock = new Clock() {
        @Override
        public long millis() {
            return now.get();
        }

        @Override
        public long nanos() {
            return now.get() * 1_000_000;
        }
    };

    private MachineEnvironments machine(List<WslDistros.Distro> distros) {
        return new MachineEnvironments(
                () -> distros,
                () -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (String d : paired) {
                        out.add(Map.of(
                                "id", "0123456789ab", "guest", "wsl", "guestName", d, "port", 8944, "paired", true));
                    }
                    return out;
                },
                new WslSetup(shell, downloads),
                () -> new ReleasePin.Pin("0.51.1", "the newest release at or below 0.51.2-SNAPSHOT"),
                scratch,
                "0.51.2-SNAPSHOT",
                clock,
                ms -> now.addAndGet(ms));
    }

    private static MachineEnvironments.Status awaitEnd(MachineEnvironments m, String name) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            MachineEnvironments.Status s = m.status(name);
            if (s != null && !s.busy()) return s;
            Thread.sleep(20);
        }
        throw new AssertionError(name + " did not finish: " + m.status(name));
    }

    @Test
    void names_wsl_does_not_list_are_refused_and_nothing_runs_for_them() {
        MachineEnvironments m = machine(List.of(UBUNTU));
        MachineEnvironments.Started started = m.start(
                MachineEnvironments.Action.INSTALL, List.of("Ubuntu-26.10; rm -rf /", "../Ubuntu-26.10", "Debian"));
        assertThat(started.accepted()).isEmpty();
        assertThat(started.refused()).containsOnlyKeys("Ubuntu-26.10; rm -rf /", "../Ubuntu-26.10", "Debian");
        assertThat(ran).isEmpty();
    }

    @Test
    void a_failing_distro_leaves_the_next_to_install_and_each_reports_its_own_outcome() throws Exception {
        MachineEnvironments m = machine(List.of(UBUNTU, BROKEN, STOPPED));
        MachineEnvironments.Started started =
                m.start(MachineEnvironments.Action.INSTALL, List.of("broken", "ubuntu-26.10", "Ubuntu-26.10"));
        // Each named distro once, by the name WSL gives it.
        assertThat(started.accepted()).containsExactly("Broken", "Ubuntu-26.10");

        MachineEnvironments.Status broken = awaitEnd(m, "Broken");
        MachineEnvironments.Status ubuntu = awaitEnd(m, "Ubuntu-26.10");
        assertThat(broken.state()).isEqualTo("failed");
        assertThat(broken.step())
                .isEqualTo("JumpKick's installer failed in Broken: jk 0.51.1 publishes no native"
                        + " linux_x86_64 client");
        assertThat(ubuntu.state()).isEqualTo("done");
        assertThat(ubuntu.step())
                .isEqualTo("installed JumpKick 0.51.1 in Ubuntu-26.10; its engine is paired with the front door");
        // Every command named the distro by WSL's name, from its own folder, which is gone afterwards.
        assertThat(ran)
                .allMatch(argv -> argv.get(0).equals("wsl.exe") && argv.get(1).equals("-d"));
        assertThat(ran).extracting(argv -> argv.get(2)).containsOnly("Broken", "Ubuntu-26.10");
        try (var left = Files.list(scratch)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void the_listing_shows_state_engine_and_progress() throws Exception {
        MachineEnvironments m = machine(List.of(UBUNTU, STOPPED));
        m.start(MachineEnvironments.Action.INSTALL, List.of("Ubuntu-26.10"));
        awaitEnd(m, "Ubuntu-26.10");
        Map<String, Object> list = m.list();
        assertThat(list.get("host"))
                .isEqualTo(Map.of("kind", "windows", "version", "0.51.2-SNAPSHOT", "installs", "0.51.1"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) Objects.requireNonNull(list.get("environments"));
        assertThat(rows).extracting(r -> r.get("name")).containsExactly("Ubuntu-26.10", "Fedora");
        assertThat(rows.get(0).get("state")).isEqualTo("not-installed");
        Map<?, ?> engine = (Map<?, ?>) Objects.requireNonNull(rows.get(0).get("engine"));
        assertThat(engine.get("id")).isEqualTo("0123456789ab");
        assertThat(engine.get("port")).isEqualTo(8944);
        assertThat(engine.get("paired")).isEqualTo(true);
        assertThat(((Map<?, ?>) Objects.requireNonNull(rows.get(0).get("operation"))).get("state"))
                .isEqualTo("done");
        assertThat(rows.get(1).get("state")).isEqualTo("unknown");
        assertThat(rows.get(1).get("operation")).isNull();
    }

    @Test
    void an_engine_that_never_pairs_fails_the_install_with_the_reason() throws Exception {
        WslShell noPair = (argv, env, limit, out) -> {
            if (argv.getLast().equals("./probe.sh")) {
                List.of("uid=1000", "has=bash", "has=curl", "has=openssl", "has=gunzip", "arch=x86_64")
                        .forEach(out);
            }
            return 0;
        };
        MachineEnvironments m = new MachineEnvironments(
                () -> List.of(UBUNTU),
                List::of,
                new WslSetup(noPair, downloads),
                () -> new ReleasePin.Pin("0.51.1", "test"),
                scratch,
                "0.51.1",
                clock,
                ms -> now.addAndGet(ms));
        m.start(MachineEnvironments.Action.INSTALL, List.of("Ubuntu-26.10"));
        MachineEnvironments.Status s = awaitEnd(m, "Ubuntu-26.10");
        assertThat(s.state()).isEqualTo("failed");
        assertThat(s.step()).contains("does not answer the front door").contains("may predate the front door");
    }

    @Test
    void a_distro_already_busy_is_refused() throws Exception {
        WslShell slow = (argv, env, limit, out) -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1;
        };
        MachineEnvironments m = new MachineEnvironments(
                () -> List.of(UBUNTU),
                List::of,
                new WslSetup(slow, downloads),
                () -> new ReleasePin.Pin("0.51.1", "test"),
                scratch,
                "0.51.1",
                clock,
                ms -> now.addAndGet(ms));
        assertThat(m.start(MachineEnvironments.Action.REMOVE, List.of("Ubuntu-26.10"))
                        .accepted())
                .containsExactly("Ubuntu-26.10");
        MachineEnvironments.Started again = m.start(MachineEnvironments.Action.INSTALL, List.of("Ubuntu-26.10"));
        assertThat(again.accepted()).isEmpty();
        assertThat(again.refused().get("Ubuntu-26.10")).startsWith("already");
        // The operation writes under the test's folder until it ends.
        awaitEnd(m, "Ubuntu-26.10");
    }
}
