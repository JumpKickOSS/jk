// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
}
