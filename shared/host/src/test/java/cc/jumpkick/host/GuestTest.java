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
        assertThat(withInterop).isEqualTo(new Guest(Guest.Kind.WSL, "Ubuntu", true));

        Guest named = detect(Set.of(), files, Map.of("WSL_DISTRO_NAME", "Ubuntu-26.10"));
        assertThat(named).isEqualTo(new Guest(Guest.Kind.WSL, "Ubuntu-26.10", false));
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
