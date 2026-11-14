// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuestTest {

    private static Guest detect(Set<Path> present, Map<Path, String> files, Map<String, String> env) {
        return Guest.detect(present::contains, files::get, env::get);
    }

    @Test
    void aWslKernelIsAGuestWithItsInteropAndDistro() {
        Map<Path, String> files = Map.of(
                Guest.OSRELEASE, "6.6.87.2-microsoft-standard-WSL2\n",
                Guest.OS_RELEASE, "PRETTY_NAME=\"Ubuntu 26.10\"\nNAME=\"Ubuntu\"\n");

        Guest withInterop = detect(Set.of(Guest.INTEROP), files, Map.of());
        assertThat(withInterop).isEqualTo(new Guest(Guest.Kind.WSL, "Ubuntu", true, ""));

        Guest named = detect(Set.of(), files, Map.of("WSL_DISTRO_NAME", "Ubuntu-26.10"));
        assertThat(named).isEqualTo(new Guest(Guest.Kind.WSL, "Ubuntu-26.10", false, "distro=Ubuntu-26.10"));
    }

    /** What a WSL distro shows any process: two Ubuntu installs on one Windows host, read live. */
    private static Map<Path, String> distro(String machineId, String device, String wwid) {
        return Map.of(
                Guest.OSRELEASE,
                "6.6.87.2-microsoft-standard-WSL2\n",
                Guest.OS_RELEASE,
                "NAME=\"Ubuntu\"\n",
                Guest.MACHINE_ID,
                machineId + "\n",
                Guest.MOUNTINFO,
                "703 665 0:34 / /usr/lib/wsl/drivers ro,nosuid - 9p drivers ro\n"
                        + "665 562 " + device + " / / rw,relatime - ext4 /dev/sdf rw,discard\n"
                        + "705 665 0:32 / /mnt/wsl rw,relatime shared:1 - tmpfs none rw\n",
                Path.of("/sys/dev/block", device, "device", "wwid"),
                wwid + "\n");
    }

    @Test
    void twoInstallsOfOneDistroImageAreToldApartWithoutTheirWslVariables() {
        Map<Path, String> first =
                distro("8be22fc4f5024a67ab9f16b3f31480b6", "8:80", "naa.60022480e0952abfdb4c9819122a93b6");
        Map<Path, String> second =
                distro("36d266172165422fbc09ec805d15162e", "8:96", "naa.60022480e2c91a8ae50d02212d6ec705");

        Guest a = detect(Set.of(), first, Map.of());
        Guest b = detect(Set.of(), second, Map.of());

        assertThat(a.name()).isEqualTo(b.name()).isEqualTo("Ubuntu");
        assertThat(a.instance())
                .isEqualTo("machine-id=8be22fc4f5024a67ab9f16b3f31480b6 disk=naa.60022480e0952abfdb4c9819122a93b6")
                .isNotEqualTo(b.instance());
        assertThat(detect(Set.of(), first, Map.of("WSL_DISTRO_NAME", "Ubuntu-26.04"))
                        .instance())
                .as("the same with or without the variable a daemon lacks")
                .isEqualTo(a.instance());
    }

    @Test
    void aDistroClonedByExportAndImportKeepsItsMachineIdAndGetsAnotherDisk() {
        String machineId = "8be22fc4f5024a67ab9f16b3f31480b6";
        Guest original = detect(Set.of(), distro(machineId, "8:80", "naa.6002248000000000000000000000000a"), Map.of());
        Guest clone = detect(Set.of(), distro(machineId, "8:96", "naa.6002248000000000000000000000000b"), Map.of());

        assertThat(original.instance()).isNotEqualTo(clone.instance());
    }

    @Test
    void theDiskIsNamedByItsIdWhicheverDeviceItIsAttachedAs() {
        String wwid = "naa.60022480e0952abfdb4c9819122a93b6";
        String machineId = "8be22fc4f5024a67ab9f16b3f31480b6";

        assertThat(detect(Set.of(), distro(machineId, "8:80", wwid), Map.of()).instance())
                .isEqualTo(detect(Set.of(), distro(machineId, "8:112", wwid), Map.of())
                        .instance());
    }

    @Test
    void aContainerIsNamedByItsIdFromTheMountOfItsHostname() {
        String id = "4f2c" + "0".repeat(56) + "beef";
        Map<Path, String> files = Map.of(
                Guest.OSRELEASE,
                "6.8.0-45-generic\n",
                Guest.MOUNTINFO,
                "500 400 0:52 / / rw - overlay overlay rw\n"
                        + "512 500 259:2 /var/lib/docker/containers/" + id
                        + "/hostname /etc/hostname rw - ext4 /dev/nvme0n1p2 rw\n");

        Guest docker = detect(Set.of(Path.of("/.dockerenv")), files, Map.of());

        assertThat(docker).isEqualTo(new Guest(Guest.Kind.CONTAINER, "docker", false, id));
        assertThat(Guest.containerId("1 0 0:52 / / rw - overlay overlay rw\n")).isEmpty();
    }

    @Test
    void containersAreGuestsAndAPlainLinuxIsNot() {
        Map<Path, String> linux = Map.of(Guest.OSRELEASE, "6.8.0-45-generic\n");

        assertThat(detect(Set.of(Path.of("/.dockerenv")), linux, Map.of()).kind())
                .isEqualTo(Guest.Kind.CONTAINER);
        assertThat(detect(Set.of(Path.of("/run/.containerenv")), linux, Map.of())
                        .name())
                .isEqualTo("podman");
        assertThat(detect(Set.of(), linux, Map.of("container", "lxc")).name()).isEqualTo("lxc");
        assertThat(detect(Set.of(), linux, Map.of())).isEqualTo(Guest.NONE);
        assertThat(detect(Set.of(Guest.INTEROP), linux, Map.of())).isEqualTo(Guest.NONE);
    }
}
