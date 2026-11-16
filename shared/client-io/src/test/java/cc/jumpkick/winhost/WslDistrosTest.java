// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class WslDistrosTest {

    private static final String UBUNTU = "{e44179b5-1e0e-423b-b12c-01d7bd3be7d4}";
    private static final String FEDORA = "{af8a8112-af4e-490c-9f30-8edb650c175d}";
    private static final String DOCKER = "{11111111-2222-3333-4444-555555555555}";

    /** The Lxss key of a machine with Ubuntu (default, WSL 2), Fedora (WSL 1) and Docker Desktop's distro. */
    private static final class Lxss implements Registry {
        private final Map<String, String> strings = Map.of(
                WslDistros.LXSS + "|DefaultDistribution", UBUNTU,
                WslDistros.LXSS + "\\" + UBUNTU + "|DistributionName", "Ubuntu-26.10",
                WslDistros.LXSS + "\\" + FEDORA + "|DistributionName", "FedoraLinux-44",
                WslDistros.LXSS + "\\" + DOCKER + "|DistributionName", "docker-desktop");
        private final Map<String, Integer> dwords = Map.of(
                WslDistros.LXSS + "\\" + UBUNTU + "|Version", 2,
                WslDistros.LXSS + "\\" + FEDORA + "|Version", 1);

        @Override
        public @Nullable Integer dword(Hive hive, String key, String value) {
            return dwords.get(key + "|" + value);
        }

        @Override
        public @Nullable String string(Hive hive, String key, String value) {
            return strings.get(key + "|" + value);
        }

        @Override
        public List<String> subkeys(Hive hive, String key) {
            return key.equals(WslDistros.LXSS) ? List.of(FEDORA, UBUNTU, DOCKER) : List.of();
        }
    }

    /** {@code wsl.exe} as it answers through a pipe: UTF-16LE, Ubuntu and Docker's distro running. */
    private static final class Wsl implements WslDistros.Runner {
        final List<List<String>> ran = new ArrayList<>();

        @Override
        public byte @Nullable [] run(List<String> argv, Duration limit) {
            ran.add(argv);
            if (argv.contains("--running"))
                return "Ubuntu-26.10\r\ndocker-desktop\r\n".getBytes(StandardCharsets.UTF_16LE);
            if (argv.contains("Ubuntu-26.10")) return "jk 0.51.0\n".getBytes(StandardCharsets.UTF_8);
            return null;
        }
    }

    @Test
    void lists_the_users_distros_and_asks_only_the_running_ones_for_jumpkick() {
        Wsl wsl = new Wsl();

        List<WslDistros.Distro> distros = WslDistros.list(new Lxss(), wsl);

        assertThat(distros)
                .containsExactly(
                        new WslDistros.Distro("FedoraLinux-44", 1, false, false, null),
                        new WslDistros.Distro("Ubuntu-26.10", 2, true, true, "jk 0.51.0"));
        assertThat(wsl.ran).as("a stopped distro is never started").noneMatch(argv -> argv.contains("FedoraLinux-44"));
        assertThat(wsl.ran).as("docker's distro is never asked").noneMatch(argv -> argv.contains("docker-desktop"));
    }

    @Test
    void a_machine_without_wsl_has_no_distros_and_runs_nothing() {
        Wsl wsl = new Wsl();

        assertThat(WslDistros.list(Registry.NONE, wsl)).isEmpty();
        assertThat(wsl.ran).isEmpty();
    }

    @Test
    void wsl_output_decodes_from_utf16_or_utf8_with_or_without_a_bom() {
        byte[] utf16 = "﻿Ubuntu\r\n".getBytes(StandardCharsets.UTF_16LE);

        assertThat(WslDistros.decode(utf16).strip()).isEqualTo("Ubuntu");
        assertThat(WslDistros.decode("Ubuntu\n".getBytes(StandardCharsets.UTF_8))
                        .strip())
                .isEqualTo("Ubuntu");
        assertThat(WslDistros.decode(null)).isEmpty();
        assertThat(WslDistros.running("Ubuntu\r\n\r\nDebian\r\n".getBytes(StandardCharsets.UTF_16LE)))
                .containsExactly("ubuntu", "debian");
    }

    @Test
    void the_container_tools_distros_are_left_out() {
        assertThat(WslDistros.tooling("docker-desktop-data")).isTrue();
        assertThat(WslDistros.tooling("rancher-desktop")).isTrue();
        assertThat(WslDistros.tooling("podman-machine-default")).isTrue();
        assertThat(WslDistros.tooling("Ubuntu")).isFalse();
    }

    @Test
    void a_container_tools_distro_is_left_out_by_the_folder_it_lives_in_whatever_its_name() {
        assertThat(WslDistros.tooling("engine", "C:\\Users\\a\\AppData\\Local\\Docker\\wsl\\main"))
                .isTrue();
        assertThat(WslDistros.tooling("rd", "C:\\Users\\a\\AppData\\Local\\rancher-desktop\\distro"))
                .isTrue();
        assertThat(WslDistros.tooling(
                        "machine", "C:\\Users\\a\\.local\\share\\containers\\podman\\machine\\wsl\\wsldist\\m"))
                .isTrue();
        assertThat(WslDistros.tooling(
                        "Ubuntu-26.10", "C:\\Users\\a\\AppData\\Local\\wsl\\{e44179b5-1e0e-423b-b12c-01d7bd3be7d4}"))
                .isFalse();
        assertThat(WslDistros.tooling("Ubuntu", null)).isFalse();
    }

    /**
     * Each running distro is asked at once: here every probe answers only once all four have started,
     * so probes asked one after another would each wait out their latch and answer nothing.
     */
    @Test
    void the_running_distros_are_asked_together() {
        List<String> names = List.of("a", "b", "c", "d");
        Map<String, String> strings = new HashMap<>();
        Map<String, Integer> dwords = new HashMap<>();
        for (String n : names) {
            strings.put(WslDistros.LXSS + "\\{" + n + "}|DistributionName", n);
            dwords.put(WslDistros.LXSS + "\\{" + n + "}|Version", 2);
        }
        Registry registry = new Registry() {
            @Override
            public @Nullable Integer dword(Hive hive, String key, String value) {
                return dwords.get(key + "|" + value);
            }

            @Override
            public @Nullable String string(Hive hive, String key, String value) {
                return strings.get(key + "|" + value);
            }

            @Override
            public List<String> subkeys(Hive hive, String key) {
                return key.equals(WslDistros.LXSS)
                        ? names.stream().map(n -> "{" + n + "}").toList()
                        : List.of();
            }
        };
        CountDownLatch started = new CountDownLatch(names.size());
        WslDistros.Runner runner = (argv, limit) -> {
            if (argv.contains("--running")) return String.join("\r\n", names).getBytes(StandardCharsets.UTF_16LE);
            started.countDown();
            try {
                if (!started.await(5, TimeUnit.SECONDS)) return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return ("jk 0.51." + argv.get(2) + "\n").getBytes(StandardCharsets.UTF_8);
        };

        List<WslDistros.Distro> distros = WslDistros.list(registry, runner);

        assertThat(distros).extracting(WslDistros.Distro::name).containsExactly("a", "b", "c", "d");
        assertThat(distros)
                .extracting(WslDistros.Distro::jumpKick)
                .containsExactly("jk 0.51.a", "jk 0.51.b", "jk 0.51.c", "jk 0.51.d");
    }
}
