// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.util.JkDirs;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The machine-setup decision as every surface asks it: the live check, the plan of recommended
 * changes, the question and its lines, and the setup that applies the answer. The CLI and the
 * engine's MCP {@code doctor} both build it here, so they propose the same changes.
 */
public final class WindowsSetup {

    /** The decision's kind in the queue. */
    public static final String KIND = "setup";

    public static final String QUESTION = "Set up this machine for faster builds";

    public static final String YES = "Apply the ticked changes (one Windows prompt)";

    private WindowsSetup() {}

    /** The check of this machine for {@code project}. */
    public static WindowsHostCheck check(@Nullable Path project) {
        return new WindowsHostCheck(
                HostFacts.live(), SetupMemo.read(SetupMemo.file(JkDirs.state())), JkDirs.home(), project);
    }

    /** The plan for {@code check}'s machine; {@code known} are projects already known to be projects. */
    public static SetupPlan plan(WindowsHostCheck check, List<Path> known) {
        return SetupPlan.of(check, discovery(check, known).propose(), ScanCost.LIVE);
    }

    static RootDiscovery discovery(WindowsHostCheck check, List<Path> known) {
        HostFacts f = check.facts();
        String profile = f.env("USERPROFILE");
        String appData = f.env("APPDATA");
        List<Path> projects = new ArrayList<>(known);
        if (check.project() != null) projects.add(0, check.project());
        List<Path> drives = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            String r = root.toString().toUpperCase(Locale.ROOT);
            if (!r.startsWith("A:") && !r.startsWith("B:") && Files.isDirectory(root)) drives.add(root);
        }
        return new RootDiscovery(
                Path.of(profile == null ? System.getProperty("user.home") : profile),
                appData == null ? null : Path.of(appData),
                check.home(),
                drives,
                projects,
                Forbidden.of(f, check.home().toString()));
    }

    /** The lines under the question: what applying does, what declining leaves, and that it is remembered. */
    public static List<String> detail() {
        return detail("Windows shows one prompt for the changes that need an administrator.");
    }

    /** {@link #detail()} with {@code prompt} saying how the Windows prompt shows on this surface. */
    public static List<String> detail(String prompt) {
        return List.of(
                prompt,
                "Declining is fine: JumpKick keeps working, only slower while Defender scans its files.",
                "The answer is remembered for this machine; `jk doctor --fix` asks again.");
    }

    /** The plan's changes as the decision's items. */
    public static List<Map<String, Object>> items(SetupPlan plan) {
        return plan.changes().stream().map(SetupChange::item).toList();
    }

    /** The setup that applies an answer on this machine. */
    public static MachineSetup machine(WindowsHostCheck check) {
        return MachineSetup.live(Forbidden.of(check.facts(), check.home().toString()), JkDirs.state());
    }
}
