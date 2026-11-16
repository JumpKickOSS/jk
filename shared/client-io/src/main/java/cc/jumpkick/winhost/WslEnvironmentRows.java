// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The setup decision's WSL rows: one per WSL 2 distro that runs and reports no JumpKick, "Install
 * JumpKick in WSL &lt;distro&gt;". A stopped distro is not woken to look, so it gets no row; the machine
 * view and {@code jk self setup-wsl} still install there. Each row is answered on its own, so
 * declining one distro declines neither the others nor the Defender rows. Applying a row hands the
 * distro to {@link Operation}: on the Windows engine, the one install operation the dashboard and
 * {@code jk self setup-wsl} use; from {@code jk doctor --fix}, that same operation through the
 * engine's API.
 */
public final class WslEnvironmentRows implements SetupEnvironment {

    /** How a row names its distro in {@link SetupChange#path}: {@code wsl:Ubuntu-26.10}. */
    public static final String PREFIX = "wsl:";

    /** Installs JumpKick into one distro and waits for it, each step to {@code progress}. */
    @FunctionalInterface
    public interface Operation {
        MachineSetup.Result install(String distro, Consumer<String> progress);
    }

    private final Operation operation;

    public WslEnvironmentRows(Operation operation) {
        this.operation = operation;
    }

    /** The row id of distro {@code name}: the same for every spelling WSL accepts. */
    public static String id(String name) {
        String digest = Hashing.sha256Hex(name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return "wsl-" + digest.substring(0, 10);
    }

    @Override
    public List<SetupChange> rows(WindowsHostCheck check) {
        List<SetupChange> out = new ArrayList<>();
        for (WslDistros.Distro d : check.facts().wslDistros()) {
            if (d.version() != 2 || !d.running() || d.jumpKick() != null) continue;
            if (!WslSetup.NAME.matcher(d.name()).matches()) continue;
            out.add(new SetupChange(
                    id(d.name()),
                    "Install JumpKick in WSL " + d.name(),
                    "Builds in " + d.name() + " get an engine of their own, paired with this one: one dashboard and"
                            + " one notifier for both.",
                    "Installs the JumpKick release that matches this one into your account there, in a few minutes,"
                            + " with no administrator prompt.",
                    SetupChange.Kind.ENVIRONMENT,
                    PREFIX + d.name()));
        }
        return out;
    }

    @Override
    public boolean owns(SetupChange row) {
        return distro(row) != null;
    }

    @Override
    public MachineSetup.Result install(SetupChange row, Consumer<String> progress) {
        String distro = distro(row);
        if (distro == null) return new MachineSetup.Result(row.id(), false, "not a WSL row: " + row.id());
        MachineSetup.Result r = operation.install(distro, progress);
        return new MachineSetup.Result(row.id(), r.ok(), r.line());
    }

    private static @Nullable String distro(SetupChange row) {
        String path = row.path();
        if (row.kind() != SetupChange.Kind.ENVIRONMENT || path == null || !path.startsWith(PREFIX)) return null;
        String name = path.substring(PREFIX.length());
        return WslSetup.NAME.matcher(name).matches() ? name : null;
    }
}
