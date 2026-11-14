// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.power;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.ProcessEnv;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** How this host tells its power source and keeps itself from idle sleep. */
public sealed interface PowerHost permits LinuxPower, MacPower, WindowsPower, PowerHost.DetectOnly {

    /** Where the machine draws its power from. */
    enum Source {
        AC,
        BATTERY,
        UNKNOWN
    }

    /** One hold on idle sleep, kept until {@link #close}. */
    interface Hold extends AutoCloseable {
        /** Whether the hold is still in force (its helper process has not exited). */
        boolean alive();

        @Override
        void close();
    }

    Source source();

    /** Starts keeping the machine awake; null when it cannot here (the reason is logged by the caller). */
    @Nullable
    Hold hold();

    /** A host whose sleep this engine cannot hold: a WSL guest, a container, CI, an unknown OS. */
    record DetectOnly(String why) implements PowerHost {
        @Override
        public Source source() {
            return Source.UNKNOWN;
        }

        @Override
        public @Nullable Hold hold() {
            return null;
        }
    }

    /** This machine's power host. */
    static PowerHost forThisHost() {
        if (ProcessEnv.get("CI") != null) return new DetectOnly("CI");
        if (Os.isWindows()) return new WindowsPower();
        if (Os.isDarwin()) return MacPower.system();
        if (Os.isLinux()) {
            if (wsl()) return new DetectOnly("a WSL guest cannot hold the Windows host awake");
            if (Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"))) {
                return new DetectOnly("a container");
            }
            return LinuxPower.system();
        }
        return new DetectOnly("an unsupported OS");
    }

    private static boolean wsl() {
        if (ProcessEnv.get("WSL_DISTRO_NAME") != null) return true;
        try {
            return Files.readString(Path.of("/proc/sys/kernel/osrelease"))
                    .toLowerCase(Locale.ROOT)
                    .contains("microsoft");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
