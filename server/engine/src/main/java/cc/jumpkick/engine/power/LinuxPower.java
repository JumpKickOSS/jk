// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.power;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Linux: the power source from {@code /sys/class/power_supply}, and an idle and sleep inhibitor held
 * by a {@code systemd-inhibit} child (logind's own client; no D-Bus library). Mains or USB power
 * online is A/C; a machine with no system battery is on A/C; otherwise a system battery runs it.
 * A peripheral's battery (a mouse's, {@code scope = Device}) is not the machine's.
 */
record LinuxPower(Path supplies, ChildHold.Starter starter) implements PowerHost {

    static LinuxPower system() {
        return new LinuxPower(Path.of("/sys/class/power_supply"), ChildHold.PROCESSES);
    }

    /** The inhibitor's argv: {@code cat} on the engine's pipe holds it until the engine closes it or goes. */
    static final List<String> INHIBIT = List.of(
            "systemd-inhibit",
            "--what=idle:sleep",
            "--who=JumpKick",
            "--why=A build is running",
            "--mode=block",
            "cat");

    @Override
    public Source source() {
        if (!Files.isDirectory(supplies)) return Source.AC;
        boolean[] battery = {false};
        boolean[] mains = {false};
        try {
            // Each entry is a link to its device: read through it.
            PathUtil.forEachChild(supplies, (s, attrs) -> {
                String type = read(s.resolve("type"));
                if ("battery".equals(type)) {
                    if (!"device".equals(read(s.resolve("scope")))) battery[0] = true;
                } else if (EnvValues.parseBool(read(s.resolve("online"))).orElse(false)) {
                    mains[0] = true;
                }
                return true;
            });
        } catch (IOException e) {
            return Source.UNKNOWN;
        }
        if (mains[0] || !battery[0]) return Source.AC;
        return Source.BATTERY;
    }

    @Override
    public @Nullable Hold hold() {
        return ChildHold.start(starter, INHIBIT);
    }

    private static @Nullable String read(Path file) {
        try {
            return Files.readString(file).strip().toLowerCase(Locale.ROOT);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
